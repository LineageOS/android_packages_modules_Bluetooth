/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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

#include <base/functional/bind.h>
#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <stdio.h>

#include <algorithm>
#include <bitset>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <deque>
#include <functional>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <ostream>
#include <sstream>
#include <tuple>
#include <utility>
#include <vector>

#include "audio_hal_client/audio_hal_client.h"
#include "audio_hal_interface/le_audio_software.h"
#include "bt_types.h"
#include "bta/csis/csis_types.h"
#include "bta_csis_api.h"
#include "bta_gatt_api.h"
#include "bta_gatt_queue.h"
#include "bta_groups.h"
#include "bta_le_audio_api.h"
#include "bta_le_audio_broadcaster_api.h"
#include "btif/include/btif_profile_storage.h"
#include "btm_api_types.h"
#include "btm_ble_api_types.h"
#include "btm_iso_api.h"
#include "btm_iso_api_types.h"
#include "btm_sec_api_types.h"
#include "client_parser.h"
#include "codec_interface.h"
#include "codec_manager.h"
#include "common/strings.h"
#include "common/time_util.h"
#include "content_control_id_keeper.h"
#include "devices.h"
#include "gatt/database.h"
#include "gatt_api.h"
#include "gattdefs.h"
#include "gmap_client.h"
#include "gmap_server.h"
#include "hardware/bt_le_audio.h"
#include "hci/controller_interface.h"
#include "hci_error_code.h"
#include "include/hardware/bt_gmap.h"
#include "internal_include/bt_trace.h"
#include "internal_include/stack_config.h"
#include "le_audio/device_groups.h"
#include "le_audio/le_audio_log_history.h"
#include "le_audio_health_status.h"
#include "le_audio_set_configuration_provider.h"
#include "le_audio_types.h"
#include "le_audio_utils.h"
#include "main/shim/entry.h"
#include "metrics_collector.h"
#include "osi/include/alarm.h"
#include "osi/include/osi.h"
#include "osi/include/properties.h"
#include "stack/btm/btm_sec.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_status.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/main_thread.h"
#include "state_machine.h"
#include "storage_helper.h"
#include "types/bluetooth/uuid.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

#ifdef TARGET_FLOSS
#include <audio_hal_interface/audio_linux.h>
#else
#include <hardware/audio.h>
#endif  // TARGET_FLOSS

using base::Closure;
using bluetooth::Uuid;
using bluetooth::common::ToString;
using bluetooth::groups::DeviceGroups;
using bluetooth::groups::DeviceGroupsCallbacks;
using bluetooth::hci::IsoManager;
using bluetooth::hci::iso_manager::cig_create_cmpl_evt;
using bluetooth::hci::iso_manager::cig_remove_cmpl_evt;
using bluetooth::hci::iso_manager::CigCallbacks;
using bluetooth::le_audio::CodecManager;
using bluetooth::le_audio::ConnectionState;
using bluetooth::le_audio::ContentControlIdKeeper;
using bluetooth::le_audio::DeviceConnectState;
using bluetooth::le_audio::DsaMode;
using bluetooth::le_audio::DsaModes;
using bluetooth::le_audio::GmapClient;
using bluetooth::le_audio::GmapServer;
using bluetooth::le_audio::GroupNodeStatus;
using bluetooth::le_audio::GroupStatus;
using bluetooth::le_audio::GroupStreamStatus;
using bluetooth::le_audio::LeAudioCodecConfiguration;
using bluetooth::le_audio::LeAudioDevice;
using bluetooth::le_audio::LeAudioDeviceGroup;
using bluetooth::le_audio::LeAudioDeviceGroups;
using bluetooth::le_audio::LeAudioDevices;
using bluetooth::le_audio::LeAudioGroupStateMachine;
using bluetooth::le_audio::LeAudioHealthBasedAction;
using bluetooth::le_audio::LeAudioHealthDeviceStatType;
using bluetooth::le_audio::LeAudioHealthGroupStatType;
using bluetooth::le_audio::LeAudioHealthStatus;
using bluetooth::le_audio::LeAudioRecommendationActionCb;
using bluetooth::le_audio::LeAudioSinkAudioHalClient;
using bluetooth::le_audio::LeAudioSourceAudioHalClient;
using bluetooth::le_audio::UnicastMonitorModeStatus;
using bluetooth::le_audio::types::ase;
using bluetooth::le_audio::types::AseState;
using bluetooth::le_audio::types::AudioContexts;
using bluetooth::le_audio::types::AudioLocations;
using bluetooth::le_audio::types::BidirectionalPair;
using bluetooth::le_audio::types::DataPathState;
using bluetooth::le_audio::types::hdl_pair;
using bluetooth::le_audio::types::hdl_pair_wrapper;
using bluetooth::le_audio::types::kLeAudioContextAllRemoteSource;
using bluetooth::le_audio::types::kLeAudioContextAllTypesArray;
using bluetooth::le_audio::types::LeAudioContextType;
using bluetooth::le_audio::types::PublishedAudioCapabilities;
using bluetooth::le_audio::utils::GetAudioContextsFromSinkMetadata;
using bluetooth::le_audio::utils::GetAudioContextsFromSourceMetadata;

using namespace bluetooth;

/* Enums */
enum class AudioReconfigurationResult {
  RECONFIGURATION_NEEDED = 0x00,
  RECONFIGURATION_NOT_NEEDED,
  RECONFIGURATION_NOT_POSSIBLE
};

enum class AudioState {
  IDLE = 0x00,
  READY_TO_START,
  STARTED,
  READY_TO_RELEASE,
  RELEASING,
};

static std::ostream& operator<<(std::ostream& os, const AudioReconfigurationResult& state) {
  switch (state) {
    case AudioReconfigurationResult::RECONFIGURATION_NEEDED:
      os << "RECONFIGURATION_NEEDED";
      break;
    case AudioReconfigurationResult::RECONFIGURATION_NOT_NEEDED:
      os << "RECONFIGURATION_NOT_NEEDED";
      break;
    case AudioReconfigurationResult::RECONFIGURATION_NOT_POSSIBLE:
      os << "RECONFIGRATION_NOT_POSSIBLE";
      break;
    default:
      os << "UNKNOWN";
      break;
  }
  return os;
}

static std::ostream& operator<<(std::ostream& os, const AudioState& audio_state) {
  switch (audio_state) {
    case AudioState::IDLE:
      os << "IDLE";
      break;
    case AudioState::READY_TO_START:
      os << "READY_TO_START";
      break;
    case AudioState::STARTED:
      os << "STARTED";
      break;
    case AudioState::READY_TO_RELEASE:
      os << "READY_TO_RELEASE";
      break;
    case AudioState::RELEASING:
      os << "RELEASING";
      break;
    default:
      os << "UNKNOWN";
      break;
  }
  return os;
}

namespace std {
template <>
struct formatter<AudioState> : ostream_formatter {};
}  // namespace std

namespace {
void le_audio_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data);

static void le_audio_health_status_callback(const RawAddress& addr, int group_id,
                                            LeAudioHealthBasedAction action);

class LeAudioClientImpl;
LeAudioClientImpl* instance;
std::mutex instance_mutex;
LeAudioSourceAudioHalClient::Callbacks* audioSinkReceiver;
LeAudioSinkAudioHalClient::Callbacks* audioSourceReceiver;
CigCallbacks* stateMachineHciCallbacks;
LeAudioGroupStateMachine::Callbacks* stateMachineCallbacks;
DeviceGroupsCallbacks* device_group_callbacks;
LeAudioIsoDataCallback* iso_data_callback;

class StreamSpeedTracker {
public:
  StreamSpeedTracker(void)
      : is_started_(false),
        group_id_(bluetooth::groups::kGroupUnknown),
        num_of_devices_(0),
        context_type_(LeAudioContextType::UNSPECIFIED),
        reconfig_start_ts_(0),
        setup_start_ts_(0),
        total_time_(0),
        reconfig_time_(0),
        stream_setup_time_(0) {}

  void Init(int group_id, LeAudioContextType context_type, int num_of_devices) {
    Reset(bluetooth::groups::kGroupUnknown);
    group_id_ = group_id;
    context_type_ = context_type;
    num_of_devices_ = num_of_devices;
    log::verbose("StreamSpeedTracker group_id: {}, context: {} #{}", group_id_,
                 ToString(context_type_), num_of_devices);
  }

  void Reset(int group_id) {
    if (group_id != bluetooth::groups::kGroupUnknown && group_id != group_id_) {
      log::verbose("StreamSpeedTracker Reset called for invalid group_id: {} != {}", group_id,
                   group_id_);
      return;
    }

    log::verbose("StreamSpeedTracker group_id: {}", group_id_);
    is_started_ = false;
    group_id_ = bluetooth::groups::kGroupUnknown;
    reconfig_start_ts_ = setup_start_ts_ = total_time_ = reconfig_time_ = stream_setup_time_ =
            num_of_devices_ = 0;
    context_type_ = LeAudioContextType::UNSPECIFIED;
  }

  void ReconfigStarted(void) {
    log::verbose("StreamSpeedTracker group_id: {}", group_id_);
    reconfig_time_ = 0;
    is_started_ = true;
    reconfig_start_ts_ = bluetooth::common::time_get_os_boottime_us();
  }

  void StartStream(void) {
    log::verbose("StreamSpeedTracker group_id: {}", group_id_);
    setup_start_ts_ = bluetooth::common::time_get_os_boottime_us();
    is_started_ = true;
  }

  void ReconfigurationComplete(void) {
    reconfig_time_ = (bluetooth::common::time_get_os_boottime_us() - reconfig_start_ts_) / 1000;
    log::verbose("StreamSpeedTracker group_id: {}, {} reconfig time {} ms", group_id_,
                 ToString(context_type_), reconfig_time_);
  }

  void StreamCreated(void) {
    stream_setup_time_ = (bluetooth::common::time_get_os_boottime_us() - setup_start_ts_) / 1000;
    log::verbose("StreamSpeedTracker group_id: {}, {} stream create  time {} ms", group_id_,
                 ToString(context_type_), stream_setup_time_);
  }

  void StopStreamSetup(void) {
    is_started_ = false;
    uint64_t start_ts = reconfig_time_ != 0 ? reconfig_start_ts_ : setup_start_ts_;
    total_time_ = (bluetooth::common::time_get_os_boottime_us() - start_ts) / 1000;
    clock_gettime(CLOCK_REALTIME, &end_ts_);
    log::verbose("StreamSpeedTracker group_id: {}, {} setup time {} ms", group_id_,
                 ToString(context_type_), total_time_);
  }

  bool IsStarted(int group_id) {
    if (is_started_ && group_id_ == group_id) {
      log::verbose("StreamSpeedTracker group_id: {}, {} is_started_: {} ", group_id_,
                   ToString(context_type_), is_started_);
      return true;
    }
    log::verbose("StreamSpeedTracker not started {} or group_id does not match ({} ! = {}) ",
                 is_started_, group_id, group_id_);
    return false;
  }

  void Dump(std::stringstream& stream) {
    char ts[20];
    std::strftime(ts, sizeof(ts), "%T", std::gmtime(&end_ts_.tv_sec));

    if (total_time_ < 900) {
      stream << "[ 🌟 ";
    } else if (total_time_ < 1500) {
      stream << "[ 🌤 ";
    } else if (total_time_ < 2500) {
      stream << "[ 🌧 ";
    } else {
      stream << "[ ❗ ";
    }

    stream << ts << ": Gid: " << group_id_ << "(#" << num_of_devices_ << "), " << context_type_
           << ", ";
    auto hal_idle = total_time_ - stream_setup_time_ - reconfig_time_;
    if (reconfig_time_ != 0) {
      stream << "t:" << total_time_ << "ms (r:" << reconfig_time_ << "/s:" << stream_setup_time_
             << "/hal:" << hal_idle << ")";
    } else {
      stream << "t:" << total_time_ << "ms (hal:" << hal_idle << ")";
    }
    stream << "]";
  }

private:
  bool is_started_;
  int group_id_;
  int num_of_devices_;
  LeAudioContextType context_type_;
  struct timespec end_ts_;
  uint64_t reconfig_start_ts_;
  uint64_t setup_start_ts_;
  uint64_t total_time_;
  uint64_t reconfig_time_;
  uint64_t stream_setup_time_;
};

/*
 * Coordinatet Set Identification Profile (CSIP) based on CSIP 1.0
 * and Coordinatet Set Identification Service (CSIS) 1.0
 *
 * CSIP allows to organize audio servers into sets e.g. Stereo Set, 5.1 Set
 * and speed up connecting it.
 *
 * Since leaudio has already grouping API it was decided to integrate here CSIS
 * and allow it to group devices semi-automatically.
 *
 * Flow:
 * If connected device contains CSIS services, and it is included into CAP
 * service, implementation marks device as a set member and waits for the
 * bta/csis to learn about groups and notify implementation about assigned
 * group id.
 *
 */
/* LeAudioClientImpl class represents main implementation class for le audio
 * feature in stack. This class implements GATT, le audio and ISO related parts.
 *
 * This class is represented in single instance and manages a group of devices,
 * and devices. All devices calls back static method from it and are dispatched
 * to target receivers (e.g. ASEs, devices).
 *
 * This instance also implements a LeAudioClient which is a upper layer API.
 * Also LeAudioClientCallbacks are callbacks for upper layer.
 *
 * This class may be bonded with Test socket which allows to drive an instance
 * for test purposes.
 */
class LeAudioClientImpl : public LeAudioClient {
public:
  ~LeAudioClientImpl() {
    alarm_free(close_vbc_timeout_);
    alarm_free(disable_timer_);
    alarm_free(suspend_timeout_);
    alarm_free(reconfiguration_timeout_);
  }

  LeAudioClientImpl(bluetooth::le_audio::LeAudioClientCallbacks* callbacks,
                    LeAudioGroupStateMachine::Callbacks* state_machine_callbacks,
                    base::Closure initCb)
      : gatt_if_(0),
        callbacks_(callbacks),
        active_group_id_(bluetooth::groups::kGroupUnknown),
        configuration_context_type_(LeAudioContextType::UNINITIALIZED),
        in_call_metadata_context_types_({.sink = AudioContexts(), .source = AudioContexts()}),
        local_metadata_context_types_({.sink = AudioContexts(), .source = AudioContexts()}),
        audio_receiver_state_(AudioState::IDLE),
        audio_sender_state_(AudioState::IDLE),
        in_call_(false),
        in_voip_call_(false),
        sink_monitor_mode_(false),
        sink_monitor_notified_status_(std::nullopt),
        source_monitor_mode_(false),
        source_monitor_notified_status_(std::nullopt),
        le_audio_source_hal_client_(nullptr),
        le_audio_sink_hal_client_(nullptr),
        close_vbc_timeout_(alarm_new("LeAudioCloseVbcTimeout")),
        suspend_timeout_(alarm_new("LeAudioSuspendTimeout")),
        reconfiguration_timeout_(alarm_new("LeAudioReconfigurationTimeout")),
        disable_timer_(alarm_new("LeAudioDisableTimer")) {
    LeAudioGroupStateMachine::Initialize(state_machine_callbacks);
    groupStateMachine_ = LeAudioGroupStateMachine::Get();

    log::info("Reconnection mode: TARGETED_ANNOUNCEMENTS");
    reconnection_mode_ = BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS;

    log::info("Loading health status module");
    leAudioHealthStatus_ = LeAudioHealthStatus::Get();
    leAudioHealthStatus_->RegisterCallback(base::BindRepeating(le_audio_health_status_callback));

    BTA_GATTC_AppRegister(
            "le_audio", le_audio_gattc_callback,
            base::Bind(
                    [](base::Closure initCb, uint8_t client_id, uint8_t status) {
                      if (status != GATT_SUCCESS) {
                        log::error("Can't start LeAudio profile - no gatt clients left!");
                        return;
                      }
                      instance->gatt_if_ = client_id;
                      initCb.Run();
                    },
                    initCb),
            true);

    DeviceGroups::Get()->Initialize(device_group_callbacks);
  }

  /* Helper function for update source local and in_call context metadata (if in call) */
  void UpdateSourceLocalMetadataContextTypes(AudioContexts contexts) {
    /* Update cached fallback contexts */
    if (IsInCall()) {
      in_call_metadata_context_types_.source = contexts;
    }

    local_metadata_context_types_.source = contexts;
  }

  /* Helper function for update sink local and in_call context metadata (if in call) */
  void UpdateSinkLocalMetadataContextTypes(AudioContexts contexts) {
    /* Update cached fallback contexts */
    if (IsInCall()) {
      in_call_metadata_context_types_.sink = contexts;
    }

    local_metadata_context_types_.sink = contexts;
  }

  void ReconfigureAfterVbcClose() {
    log::debug("VBC close timeout");

    if (IsInVoipCall()) {
      SetInVoipCall(false);
    }

    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      log::error("Invalid group: {}", active_group_id_);
      return;
    }

    /* Reconfiguration to non requiring source scenario */
    if (sink_monitor_mode_) {
      notifyAudioLocalSink(UnicastMonitorModeStatus::STREAMING_SUSPENDED);
    }

    /* For sonification events we don't really need to reconfigure to HQ
     * configuration, but if the previous configuration was for HQ Media,
     * we might want to go back to that scenario.
     */

    if ((configuration_context_type_ != LeAudioContextType::MEDIA) &&
        (configuration_context_type_ != LeAudioContextType::GAME)) {
      log::info("Keeping the old configuration as no HQ Media playback is needed right now.");
      return;
    }

    /* Test the existing metadata against the recent availability */
    local_metadata_context_types_.source &=
            group->GetAvailableContexts(bluetooth::le_audio::types::kLeAudioDirectionSink);
    if (local_metadata_context_types_.source.none()) {
      log::warn("invalid/unknown context metadata, using 'MEDIA' instead");
      UpdateSourceLocalMetadataContextTypes(AudioContexts(LeAudioContextType::MEDIA));
    }

    /* Choose the right configuration context */
    auto new_configuration_context =
            ChooseConfigurationContextType(local_metadata_context_types_.source);

    log::debug("new_configuration_context= {}", ToString(new_configuration_context));
    ReconfigureOrUpdateMetadata(group, new_configuration_context,
                                {.sink = local_metadata_context_types_.source,
                                 .source = local_metadata_context_types_.sink});
  }

  void StartVbcCloseTimeout() {
    if (alarm_is_scheduled(close_vbc_timeout_)) {
      StopVbcCloseTimeout();
    }

    static const uint64_t timeoutMs = 2000;
    log::debug("Start VBC close timeout with {} ms", timeoutMs);

    alarm_set_on_mloop(
            close_vbc_timeout_, timeoutMs,
            [](void*) {
              if (instance) {
                log::debug("Reconfigure after VBC close");
                instance->ReconfigureAfterVbcClose();
              }
            },
            nullptr);
  }

  void StopVbcCloseTimeout() {
    if (alarm_is_scheduled(close_vbc_timeout_)) {
      log::debug("Cancel VBC close timeout");
      alarm_cancel(close_vbc_timeout_);
    }
  }

  bool IsReconfigurationTimeoutRunning(
          int group_id, uint8_t direction = bluetooth::le_audio::types::kLeAudioDirectionBoth) {
    if (alarm_is_scheduled(reconfiguration_timeout_)) {
      log::debug(" is {} group_id: {}, to check: {}, scheduled: {}",
                 group_id == reconfiguration_group_ ? "running" : " not running", group_id,
                 direction, reconfiguration_local_directions_);
      return group_id == reconfiguration_group_ && (direction & reconfiguration_local_directions_);
    }
    return false;
  }

  void StartReconfigurationTimeout(int group_id) {
    log::debug("group_id: {}", group_id);

    /* This is called when Reconfiguration has been completed. This function starts
     * timer which is a guard for unwanted reconfiguration which might happen when Audio HAL
     * is sending to Bluetooth stack multiple metadata updates and suspends/resume commands.
     * What we want to achieve with this timeout, that BT stack will resume the stream with
     * configuration picked up when ReconfigurationComplete command was sent out to Audio HAL.
     */

    if (alarm_is_scheduled(reconfiguration_timeout_)) {
      log::info("Is already running for group {}", reconfiguration_group_);
      return;
    }

    auto group = aseGroups_.FindById(group_id);
    if (group == nullptr) {
      log::warn("This shall not happen, group_id: {} is not available.", group_id);
      return;
    }

    if (IsDirectionAvailableForCurrentConfiguration(
                group, bluetooth::le_audio::types::kLeAudioDirectionSink)) {
      reconfiguration_local_directions_ |= bluetooth::le_audio::types::kLeAudioDirectionSource;
    }
    if (IsDirectionAvailableForCurrentConfiguration(
                group, bluetooth::le_audio::types::kLeAudioDirectionSource)) {
      reconfiguration_local_directions_ |= bluetooth::le_audio::types::kLeAudioDirectionSink;
    }

    log::debug("reconfiguration_local_directions_ : {}", reconfiguration_local_directions_);

    reconfiguration_group_ = group_id;
    alarm_set_on_mloop(
            reconfiguration_timeout_, kAudioReconfigurationTimeoutMs,
            [](void* data) {
              if (instance) {
                instance->StopReconfigurationTimeout(
                        PTR_TO_INT(data), bluetooth::le_audio::types::kLeAudioDirectionBoth);
              }
            },
            INT_TO_PTR(group_id));
  }

  void StopReconfigurationTimeout(int group_id, uint8_t local_direction) {
    log::debug("group_id: {}, local_direction {}, reconfiguration directions {}", group_id,
               local_direction, reconfiguration_local_directions_);

    reconfiguration_local_directions_ &= ~local_direction;

    if (reconfiguration_local_directions_ != 0) {
      log::debug("Wait for remaining directions: {} ", reconfiguration_local_directions_);
      return;
    }

    if (alarm_is_scheduled(reconfiguration_timeout_)) {
      log::debug("Canceling for group_id {}", reconfiguration_group_);
      alarm_cancel(reconfiguration_timeout_);
    }
    reconfiguration_group_ = bluetooth::groups::kGroupUnknown;
  }

  void StartSuspendTimeout(void) {
    StopSuspendTimeout();

    /* Group should tie in time to get requested status */
    uint64_t timeoutMs = kAudioSuspentKeepIsoAliveTimeoutMs;
    timeoutMs = osi_property_get_int32(kAudioSuspentKeepIsoAliveTimeoutMsProp, timeoutMs);

    if (stack_config_get_interface()->get_pts_le_audio_disable_ases_before_stopping()) {
      timeoutMs += kAudioDisableTimeoutMs;
    }

    log::debug("Stream suspend_timeout_ started: {} ms", static_cast<int>(timeoutMs));

    alarm_set_on_mloop(
            suspend_timeout_, timeoutMs,
            [](void* data) {
              if (instance) {
                auto const group_id = PTR_TO_INT(data);
                log::debug("No resume request received. Stop the group ID: {}", group_id);
                instance->GroupStop(group_id);
              }
            },
            INT_TO_PTR(active_group_id_));
  }

  void StopSuspendTimeout(void) {
    if (alarm_is_scheduled(suspend_timeout_)) {
      log::debug("Cancel suspend timeout");
      alarm_cancel(suspend_timeout_);
    }
  }

  void AseInitialStateReadRequest(LeAudioDevice* leAudioDevice) {
    int ases_num = leAudioDevice->ases_.size();
    bool is_eatt_supported = gatt_profile_get_eatt_support_by_conn_id(leAudioDevice->conn_id_);

    void* notify_flag_ptr = NULL;

    tBTA_GATTC_MULTI multi_read{};

    for (int i = 0; i < ases_num; i++) {
      /* Last read ase characteristic should issue connected state callback
       * to upper layer
       */

      if (leAudioDevice->notify_connected_after_read_ && (i == (ases_num - 1))) {
        notify_flag_ptr = INT_TO_PTR(leAudioDevice->notify_connected_after_read_);
      }

      if (!com::android::bluetooth::flags::le_ase_read_multiple_variable() || !is_eatt_supported) {
        BtaGattQueue::ReadCharacteristic(leAudioDevice->conn_id_,
                                         leAudioDevice->ases_[i].hdls.val_hdl, OnGattReadRspStatic,
                                         notify_flag_ptr);
        continue;
      }

      if (i != 0 && (i % GATT_MAX_READ_MULTI_HANDLES == 0)) {
        multi_read.num_attr = GATT_MAX_READ_MULTI_HANDLES;
        BtaGattQueue::ReadMultiCharacteristic(leAudioDevice->conn_id_, multi_read,
                                              OnGattReadMultiRspStatic, notify_flag_ptr);
        memset(multi_read.handles, 0, GATT_MAX_READ_MULTI_HANDLES * sizeof(uint16_t));
      }
      multi_read.handles[i % GATT_MAX_READ_MULTI_HANDLES] = leAudioDevice->ases_[i].hdls.val_hdl;
    }

    if (com::android::bluetooth::flags::le_ase_read_multiple_variable() &&
        (ases_num % GATT_MAX_READ_MULTI_HANDLES != 0)) {
      multi_read.num_attr = ases_num % GATT_MAX_READ_MULTI_HANDLES;
      BtaGattQueue::ReadMultiCharacteristic(leAudioDevice->conn_id_, multi_read,
                                            OnGattReadMultiRspStatic, notify_flag_ptr);
    }
  }

  void OnGroupAddedCb(const RawAddress& address, const bluetooth::Uuid& uuid, int group_id) {
    log::info("address: {} group uuid {} group_id: {}", address, uuid, group_id);

    /* We are interested in the groups which are in the context of CAP */
    if (uuid != bluetooth::le_audio::uuid::kCapServiceUuid) {
      return;
    }

    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      return;
    }
    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      log::info("group already set: {}", leAudioDevice->group_id_);
      return;
    }

    group_add_node(group_id, address);
  }

  /* If device participates in streaming the group, it has to be stopped and
   * group needs to be reconfigured if needed to new configuration without
   * considering this removing device.
   */
  void SetDeviceAsRemovePendingAndStopGroup(LeAudioDevice* leAudioDevice) {
    log::info("device {}", leAudioDevice->address_);
    leAudioDevice->SetConnectionState(DeviceConnectState::REMOVING);
    leAudioDevice->closing_stream_for_disconnection_ = true;
    GroupStop(leAudioDevice->group_id_);
  }

  void OnGroupMemberAddedCb(const RawAddress& address, int group_id) {
    log::info("address: {} group_id: {}", address, group_id);

    auto group = aseGroups_.FindById(group_id);
    if (!group) {
      log::error("Not interested in group id: {}", group_id);
      return;
    }

    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      return;
    }
    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      log::info("group already set: {}", leAudioDevice->group_id_);
      return;
    }

    if (leAudioHealthStatus_) {
      leAudioHealthStatus_->AddStatisticForDevice(leAudioDevice,
                                                  LeAudioHealthDeviceStatType::VALID_CSIS);
    }

    group_add_node(group_id, address);
  }

  void OnGroupMemberRemovedCb(const RawAddress& address, int group_id) {
    log::info("address: {} group_id: {}", address, group_id);

    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      return;
    }
    if (leAudioDevice->group_id_ != group_id) {
      log::warn("Device: {} not assigned to the group.", leAudioDevice->address_);
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    if (group == NULL) {
      log::info("device not in the group: {}, {}", leAudioDevice->address_, group_id);
      return;
    }

    if (leAudioHealthStatus_) {
      leAudioHealthStatus_->RemoveStatistics(address, group->group_id_);
    }

    if (leAudioDevice->HaveActiveAse()) {
      SetDeviceAsRemovePendingAndStopGroup(leAudioDevice);
      return;
    }

    group_remove_node(group, address);
  }

  /* This callback happens if kLeAudioDeviceSetStateTimeoutMs timeout happens
   * during transition from origin to target state
   */
  void OnLeAudioDeviceSetStateTimeout(int group_id) {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    if (!group) {
      /* Group removed */
      return;
    }

    bool check_if_recovery_needed =
            group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE;

    if (leAudioHealthStatus_) {
      leAudioHealthStatus_->AddStatisticForGroup(
              group, LeAudioHealthGroupStatType::STREAM_CREATE_SIGNALING_FAILED);
    }

    log::error(
            "State not achieved on time for group: group id {}, current state {}, "
            "target state: {}, check_if_recovery_needed: {}",
            group_id, ToString(group->GetState()), ToString(group->GetTargetState()),
            check_if_recovery_needed);
    group->PrintDebugState();
    group->SetTargetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
    group->ClearAllCises();

    /* There is an issue with a setting up stream or any other operation which
     * are gatt operations. It means peer is not responsible. Lets close ACL
     */
    CancelStreamingRequest();
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    if (leAudioDevice == nullptr) {
      log::error("Shouldn't be called without an active device.");
      leAudioDevice = group->GetFirstDevice();
      if (leAudioDevice == nullptr) {
        log::error("Front device is null. Number of devices: {}", group->Size());
        return;
      }
    }

    /* If Timeout happens on stream close and stream is closing just for the
     * purpose of device disconnection, do not bother with recovery mode
     */
    bool recovery = true;
    if (check_if_recovery_needed) {
      for (auto tmpDevice = leAudioDevice; tmpDevice != nullptr;
           tmpDevice = group->GetNextActiveDevice(tmpDevice)) {
        if (tmpDevice->closing_stream_for_disconnection_) {
          recovery = false;
          break;
        }
      }
    }

    do {
      DisconnectDevice(leAudioDevice, true, recovery);
      leAudioDevice = group->GetNextActiveDevice(leAudioDevice);
    } while (leAudioDevice);

    if (recovery && !group->NumOfConnected()) {
      log::info("All devices disconnected, group becomes inactive");
      /* Both devices will  be disconnected soon. Notify upper layer that group
       * is inactive */
      groupSetAndNotifyInactive();
    }
  }

  void UpdateLocationsAndContextsAvailability(LeAudioDeviceGroup* group,
                                              bool available_contexts_changed = false) {
    bool group_conf_changed = group->ReloadAudioLocations();
    group_conf_changed |= group->ReloadAudioDirections();

    log::verbose("group_id: {}, group_conf_changed: {} available_contexts_changed: {}",
                 group->group_id_, group_conf_changed, available_contexts_changed);
    if (group_conf_changed || available_contexts_changed) {
      /* All the configurations should be recalculated for the new conditions */
      group->InvalidateCachedConfigurations();
      group->InvalidateGroupStrategy();
      callbacks_->OnAudioConf(group->audio_directions_, group->group_id_,
                              group->audio_locations_.sink, group->audio_locations_.source,
                              group->GetAvailableContexts().value());
    }
  }

  void SuspendedForReconfiguration() {
    if (audio_sender_state_ > AudioState::IDLE) {
      LeAudioLogHistory::Get()->AddLogHistory(kLogBtCallAf, active_group_id_, RawAddress::kEmpty,
                                              kLogAfSuspendForReconfig + "LocalSource",
                                              "r_state: " + ToString(audio_receiver_state_) +
                                                      "s_state: " + ToString(audio_sender_state_));
      le_audio_source_hal_client_->SuspendedForReconfiguration();
    }
    if (audio_receiver_state_ > AudioState::IDLE) {
      LeAudioLogHistory::Get()->AddLogHistory(kLogBtCallAf, active_group_id_, RawAddress::kEmpty,
                                              kLogAfSuspendForReconfig + "LocalSink",
                                              "r_state: " + ToString(audio_receiver_state_) +
                                                      "s_state: " + ToString(audio_sender_state_));
      le_audio_sink_hal_client_->SuspendedForReconfiguration();
    }
    StartReconfigurationTimeout(active_group_id_);
  }

  void ReconfigurationComplete(uint8_t directions) {
    if (directions & bluetooth::le_audio::types::kLeAudioDirectionSink) {
      LeAudioLogHistory::Get()->AddLogHistory(kLogBtCallAf, active_group_id_, RawAddress::kEmpty,
                                              kLogAfReconfigComplete + "LocalSource",
                                              "r_state: " + ToString(audio_receiver_state_) +
                                                      "s_state: " + ToString(audio_sender_state_));

      le_audio_source_hal_client_->ReconfigurationComplete();
    }
    if (directions & bluetooth::le_audio::types::kLeAudioDirectionSource) {
      LeAudioLogHistory::Get()->AddLogHistory(kLogBtCallAf, active_group_id_, RawAddress::kEmpty,
                                              kLogAfReconfigComplete + "LocalSink",
                                              "r_state: " + ToString(audio_receiver_state_) +
                                                      "s_state: " + ToString(audio_sender_state_));

      le_audio_sink_hal_client_->ReconfigurationComplete();
    }
  }

  void CancelLocalAudioSourceStreamingRequest() {
    le_audio_source_hal_client_->CancelStreamingRequest();

    LeAudioLogHistory::Get()->AddLogHistory(kLogBtCallAf, active_group_id_, RawAddress::kEmpty,
                                            kLogAfCancel + "LocalSource",
                                            "s_state: " + ToString(audio_sender_state_));

    audio_sender_state_ = AudioState::IDLE;
  }

  void CancelLocalAudioSinkStreamingRequest() {
    le_audio_sink_hal_client_->CancelStreamingRequest();

    LeAudioLogHistory::Get()->AddLogHistory(kLogBtCallAf, active_group_id_, RawAddress::kEmpty,
                                            kLogAfCancel + "LocalSink",
                                            "s_state: " + ToString(audio_receiver_state_));

    audio_receiver_state_ = AudioState::IDLE;
  }

  void CancelStreamingRequest() {
    if (audio_sender_state_ >= AudioState::READY_TO_START) {
      CancelLocalAudioSourceStreamingRequest();
    }

    if (audio_receiver_state_ >= AudioState::READY_TO_START) {
      CancelLocalAudioSinkStreamingRequest();
    }
  }

  void group_add_node(const int group_id, const RawAddress& address,
                      bool update_group_module = false) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    LeAudioDeviceGroup* new_group;
    LeAudioDeviceGroup* old_group = nullptr;

    if (!leAudioDevice) {
      /* TODO This part possible to remove as this is to handle adding device to
       * the group which is unknown and not connected.
       */
      log::info("leAudioDevice unknown , address: {} group: 0x{:x}", address, group_id);

      if (group_id == bluetooth::groups::kGroupUnknown) {
        return;
      }

      log::info("Set member adding ...");
      leAudioDevices_.Add(address, DeviceConnectState::CONNECTING_BY_USER);
      leAudioDevice = leAudioDevices_.FindByAddress(address);
    } else {
      if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
        old_group = aseGroups_.FindById(leAudioDevice->group_id_);
      }
    }

    auto id = DeviceGroups::Get()->GetGroupId(address, bluetooth::le_audio::uuid::kCapServiceUuid);
    if (group_id == bluetooth::groups::kGroupUnknown) {
      if (id == bluetooth::groups::kGroupUnknown) {
        DeviceGroups::Get()->AddDevice(address, bluetooth::le_audio::uuid::kCapServiceUuid);
        /* We will get back here when group will be created */
        return;
      }

      new_group = aseGroups_.Add(id);
      if (!new_group) {
        log::error("can't create group - group is already there?");
        return;
      }
    } else {
      log::assert_that(id == group_id, "group id missmatch? leaudio id: {}, groups module {}",
                       group_id, id);
      new_group = aseGroups_.FindById(group_id);
      if (!new_group) {
        new_group = aseGroups_.Add(group_id);
      } else {
        if (new_group->IsDeviceInTheGroup(leAudioDevice)) {
          return;
        }
      }
    }

    log::debug("New group {}, id: {}", std::format_ptr(new_group), new_group->group_id_);

    /* If device was in the group and it was not removed by the application,
     * lets do it now
     */
    if (old_group) {
      group_remove_node(old_group, address, update_group_module);
    }

    new_group->AddNode(leAudioDevices_.GetByAddress(address));

    callbacks_->OnGroupNodeStatus(address, new_group->group_id_, GroupNodeStatus::ADDED);

    /* If device is connected and added to the group, lets read ASE states */
    if (leAudioDevice->conn_id_ != GATT_INVALID_CONN_ID) {
      AseInitialStateReadRequest(leAudioDevice);
    }

    if (leAudioDevice->GetConnectionState() == DeviceConnectState::CONNECTED) {
      UpdateLocationsAndContextsAvailability(new_group);
    }
  }

  void GroupAddNode(const int group_id, const RawAddress& address) override {
    auto id = DeviceGroups::Get()->GetGroupId(address, bluetooth::le_audio::uuid::kCapServiceUuid);
    if (id == group_id) {
      return;
    }

    if (id != bluetooth::groups::kGroupUnknown) {
      DeviceGroups::Get()->RemoveDevice(address, id);
    }

    DeviceGroups::Get()->AddDevice(address, bluetooth::le_audio::uuid::kCapServiceUuid, group_id);
  }

  void remove_group_if_possible(LeAudioDeviceGroup* group) {
    if (!group) {
      log::debug("group is null");
      return;
    }
    log::debug("Group {}, id: {}, size: {}, is cig_state {}", std::format_ptr(group),
               group->group_id_, group->Size(), ToString(group->cig.GetState()));
    if (group->IsEmpty() && (group->cig.GetState() == bluetooth::le_audio::types::CigState::NONE)) {
      lastNotifiedGroupStreamStatusMap_.erase(group->group_id_);
      aseGroups_.Remove(group->group_id_);
    }
  }

  void group_remove_node(LeAudioDeviceGroup* group, const RawAddress& address,
                         bool update_group_module = false) {
    int group_id = group->group_id_;
    group->RemoveNode(leAudioDevices_.GetByAddress(address));

    if (update_group_module) {
      int groups_group_id =
              DeviceGroups::Get()->GetGroupId(address, bluetooth::le_audio::uuid::kCapServiceUuid);
      if (groups_group_id == group_id) {
        DeviceGroups::Get()->RemoveDevice(address, group_id);
      }
    }

    callbacks_->OnGroupNodeStatus(address, group_id, GroupNodeStatus::REMOVED);

    /* Remove group if this was the last leAudioDevice in this group */
    if (group->IsEmpty()) {
      remove_group_if_possible(group);
      return;
    }

    /* Removing node from group requires updating group context availability */
    UpdateLocationsAndContextsAvailability(group);
  }

  void GroupRemoveNode(const int group_id, const RawAddress& address) override {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    log::info("group_id: {} address: {}", group_id, address);

    if (!leAudioDevice) {
      log::error("Skipping unknown leAudioDevice, address: {}", address);
      return;
    }

    if (leAudioDevice->group_id_ != group_id) {
      log::error("Device is not in group_id: {}, but in group_id: {}", group_id,
                 leAudioDevice->group_id_);
      return;
    }

    if (group == NULL) {
      log::error("device not in the group ?!");
      return;
    }

    if (leAudioDevice->HaveActiveAse()) {
      SetDeviceAsRemovePendingAndStopGroup(leAudioDevice);
      return;
    }

    group_remove_node(group, address, true);
  }

  AudioContexts ChooseMetadataContextType(AudioContexts metadata_context_type) {
    /* This function takes already filtered contexts which we are plannig to use
     * in the Enable or UpdateMetadata command.
     * Note we are not changing stream configuration here, but just the list of
     * the contexts in the Metadata which will be provide to remote side.
     * Ideally, we should send all the bits we have, but not all headsets like
     * it.
     */
    if (osi_property_get_bool(kAllowMultipleContextsInMetadata, true)) {
      return metadata_context_type;
    }

    log::debug("Converting to single context type: {}", metadata_context_type.to_string());

    /* Mini policy */
    if (metadata_context_type.any()) {
      LeAudioContextType context_priority_list[] = {
              /* Highest priority first */
              LeAudioContextType::CONVERSATIONAL, LeAudioContextType::RINGTONE,
              LeAudioContextType::LIVE,           LeAudioContextType::VOICEASSISTANTS,
              LeAudioContextType::GAME,           LeAudioContextType::MEDIA,
              LeAudioContextType::EMERGENCYALARM, LeAudioContextType::ALERTS,
              LeAudioContextType::INSTRUCTIONAL,  LeAudioContextType::NOTIFICATIONS,
              LeAudioContextType::SOUNDEFFECTS,
      };
      for (auto ct : context_priority_list) {
        if (metadata_context_type.test(ct)) {
          log::debug("Converted to single context type: {}", ToString(ct));
          return AudioContexts(ct);
        }
      }
    }

    /* Fallback to BAP mandated context type */
    log::warn("Invalid/unknown context, using 'UNSPECIFIED'");
    return AudioContexts(LeAudioContextType::UNSPECIFIED);
  }

  /* Return true if stream is started */
  bool GroupStream(LeAudioDeviceGroup* group, LeAudioContextType configuration_context_type,
                   BidirectionalPair<AudioContexts> remote_contexts) {
    log::assert_that(group != nullptr, "Group shall not be null");

    log::debug(
            "configuration_context_type= {}, remote sink contexts= {}, remote source contexts= {}",
            ToString(configuration_context_type), ToString(remote_contexts.sink),
            ToString(remote_contexts.source));

    if (configuration_context_type >= LeAudioContextType::RFU) {
      log::error("stream context type is not supported: {}",
                 ToHexString(configuration_context_type));
      return false;
    }

    log::debug("group state={}, target_state={}", ToString(group->GetState()),
               ToString(group->GetTargetState()));

    if (!group->IsAnyDeviceConnected()) {
      log::error("group {} is not connected", group->group_id_);
      return false;
    }

    /* Check if any group is in the transition state. If so, we don't allow to
     * start new group to stream
     */
    if (group->IsInTransition()) {
      /* WARNING: Due to group state machine limitations, we should not
       * interrupt any ongoing transition. We will check if another
       * reconfiguration is needed once the group reaches streaming state.
       */
      log::warn(
              "Group is already in the transition state. Waiting for the target "
              "state to be reached.");
      return false;
    }

    /* Do not put the TBS CCID when not using Telecom for the VoIP calls. */
    auto ccid_contexts = remote_contexts;
    if (IsInVoipCall() && !IsInCall()) {
      ccid_contexts.sink.unset(LeAudioContextType::CONVERSATIONAL);
      ccid_contexts.source.unset(LeAudioContextType::CONVERSATIONAL);
    }

    bool group_is_streaming = group->IsStreaming();

    BidirectionalPair<std::vector<uint8_t>> ccids = {
            .sink = ContentControlIdKeeper::GetInstance()->GetAllCcids(ccid_contexts.sink),
            .source = ContentControlIdKeeper::GetInstance()->GetAllCcids(ccid_contexts.source)};
    if (group->IsPendingConfiguration()) {
      return groupStateMachine_->ConfigureStream(group, configuration_context_type_,
                                                 remote_contexts, ccids);
    } else if (!group_is_streaming) {
      speed_start_setup(group->group_id_, configuration_context_type, group->NumOfConnected());
    }

    /* If assistant have some connected delegators that needs to be informed
     * when there would be request to stream unicast.
     */
    if (!sink_monitor_mode_ && source_monitor_mode_ && !group_is_streaming) {
      notifyAudioLocalSource(UnicastMonitorModeStatus::STREAMING_REQUESTED);
    }

    bool result = groupStateMachine_->StartStream(group, configuration_context_type,
                                                  remote_contexts, ccids);

    if (result && !group_is_streaming) {
      /* Notify Java about new configuration when start stream has been accepted and
       * it is not metadata update
       */
      SendAudioGroupCurrentCodecConfigChanged(group);
    }

    return result;
  }

  void GroupStream(const int group_id, uint16_t context_type) override {
    BidirectionalPair<AudioContexts> initial_contexts = {AudioContexts(context_type),
                                                         AudioContexts(context_type)};
    auto group = aseGroups_.FindById(group_id);
    if (!group) {
      log::error("unknown group id: {}", group_id);
      return;
    }

    GroupStream(group, LeAudioContextType(context_type), initial_contexts);
  }

  void GroupSuspend(const int group_id) override {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    if (!group) {
      log::error("unknown group id: {}", group_id);
      return;
    }

    if (!group->IsAnyDeviceConnected()) {
      log::error("group is not connected");
      return;
    }

    if (group->IsInTransition()) {
      log::info(", group is in transition from: {} to: {}", ToString(group->GetState()),
                ToString(group->GetTargetState()));
      return;
    }

    if (group->GetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      log::error(", invalid current state of group: {}", ToString(group->GetState()));
      return;
    }

    groupStateMachine_->SuspendStream(group);
  }

  void GroupStop(const int group_id) override {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    if (!group) {
      log::error("unknown group id: {}", group_id);
      return;
    }

    if (group->IsEmpty()) {
      log::error("group is empty");
      return;
    }

    groupStateMachine_->StopStream(group);
  }

  void GroupDestroy(const int group_id) override {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    if (!group) {
      log::error("unknown group id: {}", group_id);
      return;
    }

    // Disconnect and remove each device within the group
    auto* dev = group->GetFirstDevice();
    while (dev) {
      auto* next_dev = group->GetNextDevice(dev);
      RemoveDevice(dev->address_);
      dev = next_dev;
    }
  }

  void SetCodecConfigPreference(
          int group_id, bluetooth::le_audio::btle_audio_codec_config_t input_codec_config,
          bluetooth::le_audio::btle_audio_codec_config_t output_codec_config) override {
    if (!com::android::bluetooth::flags::leaudio_set_codec_config_preference()) {
      log::debug("leaudio_set_codec_config_preference flag is not enabled");
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    if (!group) {
      log::error("Unknown group id: %d", group_id);
    }

    if (group->SetPreferredAudioSetConfiguration(input_codec_config, output_codec_config)) {
      log::info("group id: {}, setting preferred codec is successful.", group_id);
    } else {
      log::warn("group id: {}, setting preferred codec is failed.", group_id);
      return;
    }

    if (group_id != active_group_id_) {
      log::warn("Selected group is not active.");
      return;
    }

    if (SetConfigurationAndStopStreamWhenNeeded(group, group->GetConfigurationContextType())) {
      log::debug("Group id {} do the reconfiguration based on preferred codec config", group_id);
    } else {
      log::debug("Group id {} preferred codec config is not changed", group_id);
    }
  }

  bool IsUsingPreferredCodecConfig(int group_id, int context_type) {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    if (!group) {
      log::error("Unknown group id: %d", group_id);
      return false;
    }

    return group->IsUsingPreferredAudioSetConfiguration(
            static_cast<LeAudioContextType>(context_type));
  }

  void SetCcidInformation(int ccid, int context_type) override {
    log::debug("Ccid: {}, context type {}", ccid, context_type);

    ContentControlIdKeeper::GetInstance()->SetCcid(AudioContexts(context_type), ccid);
  }

  void initReconfiguration(LeAudioDeviceGroup* group, LeAudioContextType previous_context_type) {
    log::debug(" group_id: {}, previous context_type {}", group->group_id_,
               ToString(previous_context_type));
    pre_configuration_context_type_ = previous_context_type;
    group->SetPendingConfiguration();
    groupStateMachine_->StopStream(group);
    speed_start_setup(group->group_id_, configuration_context_type_, group->NumOfConnected(), true);
  }

  void SetInCall(bool in_call) override {
    log::debug("in_call: {}", in_call);
    if (in_call == in_call_) {
      log::verbose("no state change {}", in_call);
      return;
    }

    in_call_ = in_call;

    if (!com::android::bluetooth::flags::leaudio_speed_up_reconfiguration_between_call()) {
      log::debug("leaudio_speed_up_reconfiguration_between_call flag is not enabled");
      return;
    }

    if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
      log::debug("There is no active group");
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
    if (!group || !group->IsStreaming()) {
      log::debug("{} is not streaming", active_group_id_);
      return;
    }

    bool reconfigure = false;

    if (in_call_) {
      in_call_metadata_context_types_ = local_metadata_context_types_;

      log::debug("in_call_metadata_context_types_ sink: {}  source: {}",
                 in_call_metadata_context_types_.sink.to_string(),
                 in_call_metadata_context_types_.source.to_string());

      auto audio_set_conf = group->GetConfiguration(LeAudioContextType::CONVERSATIONAL);
      if (audio_set_conf && group->IsGroupConfiguredTo(*audio_set_conf)) {
        log::info("Call is coming, but CIG already set for a call");
        return;
      }
      log::info("Call is coming, speed up reconfiguration for a call");
      local_metadata_context_types_.sink.clear();
      local_metadata_context_types_.source.clear();
      reconfigure = true;
    } else {
      if (configuration_context_type_ == LeAudioContextType::CONVERSATIONAL) {
        log::info("Call is ended, speed up reconfiguration for media");
        // Preemptively remove conversational context for reconfiguration speed up
        in_call_metadata_context_types_.sink.unset(LeAudioContextType::CONVERSATIONAL);
        in_call_metadata_context_types_.source.unset(LeAudioContextType::CONVERSATIONAL);
        if (in_call_metadata_context_types_.sink.none() &&
            in_call_metadata_context_types_.source.none()) {
          log::debug("No metadata, set default Media");
          in_call_metadata_context_types_.source.set(LeAudioContextType::MEDIA);
        }
        local_metadata_context_types_ = in_call_metadata_context_types_;
        log::debug("restored local_metadata_context_types_ sink: {}  source: {}",
                   local_metadata_context_types_.sink.to_string(),
                   local_metadata_context_types_.source.to_string());
        in_call_metadata_context_types_.sink.clear();
        in_call_metadata_context_types_.source.clear();
        reconfigure = true;
      }

      /* When inCall mode is disabled and remaining metadata is no longer supported by group -
       * stream should be stopped.
       */
      if (com::android::bluetooth::flags::leaudio_stop_updated_to_not_available_context_stream()) {
        if (stopStreamIfCurrentContextTypeIsNotAllowed(
                    bluetooth::le_audio::types::kLeAudioDirectionSource, group,
                    local_metadata_context_types_.sink)) {
          log::info(
                  "After disable InCall mode, updated sink metadata contexts are not allowed "
                  "context types: {} | configured: {} vs allowed context mask: {}",
                  ToString(local_metadata_context_types_.sink),
                  ToString(configuration_context_type_),
                  ToString(group->GetAllowedContextMask(
                          bluetooth::le_audio::types::kLeAudioDirectionSource)));
          return;
        }

        if (stopStreamIfCurrentContextTypeIsNotAllowed(
                           bluetooth::le_audio::types::kLeAudioDirectionSink, group,
                           local_metadata_context_types_.source)) {
          log::info(
                  "After disable InCall mode, updated source metadata contexts are not allowed "
                  "context types: {} | configured: {} vs allowed context mask: {}",
                  ToString(local_metadata_context_types_.source),
                  ToString(configuration_context_type_),
                  ToString(group->GetAllowedContextMask(
                          bluetooth::le_audio::types::kLeAudioDirectionSink)));
          return;
        }
      }
    }

    if (reconfigure) {
      ReconfigureOrUpdateRemote(group, bluetooth::le_audio::types::kLeAudioDirectionSink);
    }
  }

  bool IsInCall() override { return in_call_; }

  void SetInVoipCall(bool in_call) override {
    log::debug("in_voip_call: {}", in_call);
    in_voip_call_ = in_call;
  }

  bool IsInVoipCall() override { return in_voip_call_; }

  bool IsInVoipOrRegularCall() { return IsInCall() || IsInVoipCall(); }

  bool IsInStreaming() override {
    return audio_sender_state_ == AudioState::STARTED ||
           audio_receiver_state_ == AudioState::STARTED;
  }

  void SetUnicastMonitorMode(uint8_t direction, bool enable) override {
    if (direction == bluetooth::le_audio::types::kLeAudioDirectionSink) {
      /* Cleanup Sink HAL client interface if listening mode is toggled off
       * before group activation (active group context would take care of
       * Sink HAL client cleanup).
       */
      if (!com::android::bluetooth::flags::leaudio_use_audio_recording_listener()) {
        if (sink_monitor_mode_ && !enable && le_audio_sink_hal_client_ &&
            active_group_id_ == bluetooth::groups::kGroupUnknown) {
          local_metadata_context_types_.sink.clear();
          le_audio_sink_hal_client_->Stop();
          le_audio_sink_hal_client_.reset();
        }
      }

      log::debug("enable: {}", enable);
      sink_monitor_mode_ = enable;
    } else if (direction == bluetooth::le_audio::types::kLeAudioDirectionSource) {
      log::debug("enable: {}", enable);
      source_monitor_mode_ = enable;

      if (!enable) {
        return;
      }

      LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
      if (!group) {
        notifyAudioLocalSource(UnicastMonitorModeStatus::STREAMING_SUSPENDED);

        return;
      }

      if (group->IsStreaming()) {
        notifyAudioLocalSource(UnicastMonitorModeStatus::STREAMING);
      } else {
        notifyAudioLocalSource(UnicastMonitorModeStatus::STREAMING_SUSPENDED);
      }
    } else {
      log::error("invalid direction: 0x{:02x} monitor mode set", direction);
    }
  }

  void SendAudioProfilePreferences(const int group_id, bool is_output_preference_le_audio,
                                   bool is_duplex_preference_le_audio) override {
    log::info(
            "group_id: {}, is_output_preference_le_audio: {}, "
            "is_duplex_preference_le_audio: {}",
            group_id, is_output_preference_le_audio, is_duplex_preference_le_audio);
    if (group_id == bluetooth::groups::kGroupUnknown) {
      log::warn("Unknown group_id");
      return;
    }
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    if (!group) {
      log::warn("group_id {} does not exist", group_id);
      return;
    }

    group->is_output_preference_le_audio = is_output_preference_le_audio;
    group->is_duplex_preference_le_audio = is_duplex_preference_le_audio;
  }

  void SetGroupAllowedContextMask(int group_id, int sink_context_types,
                                  int source_context_types) override {
    log::info("group_id: {}, sink context types: {}, source context types: {}", group_id,
              sink_context_types, source_context_types);

    if (group_id == bluetooth::groups::kGroupUnknown) {
      log::warn("Unknown group_id");
      return;
    }
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    if (!group) {
      log::warn("group_id {} does not exist", group_id);
      return;
    }

    BidirectionalPair<AudioContexts> allowed_contexts = {
            .sink = AudioContexts(sink_context_types),
            .source = AudioContexts(source_context_types),
    };

    group->SetAllowedContextMask(allowed_contexts);
  }

  void StartAudioSession(LeAudioDeviceGroup* group) {
    /* This function is called when group is not yet set to active.
     * This is why we don't have to check if session is started already.
     * Just check if it is acquired.
     */
    log::assert_that(active_group_id_ == bluetooth::groups::kGroupUnknown,
                     "Active group is not set.");
    log::assert_that(le_audio_source_hal_client_ != nullptr, "Source session not acquired");
    log::assert_that(le_audio_sink_hal_client_ != nullptr, "Sink session not acquired");

    DsaModes dsa_modes = {DsaMode::DISABLED};
    dsa_modes = group->GetAllowedDsaModes();

    /* We assume that peer device always use same frame duration */
    uint32_t frame_duration_us = 0;
    if (!current_encoder_config_.IsInvalid()) {
      frame_duration_us = current_encoder_config_.data_interval_us;
    } else if (!current_decoder_config_.IsInvalid()) {
      frame_duration_us = current_decoder_config_.data_interval_us;
    } else {
      log::assert_that(true, "Both configs are invalid");
    }

    stack::l2cap::get_interface().L2CA_SetEcosystemBaseInterval(frame_duration_us / 1250);

    // Scale by the codec frame blocks per SDU if set
    uint8_t codec_frame_blocks_per_sdu =
            group->stream_conf.stream_params.source.stream_config.codec_frames_blocks_per_sdu ?: 1;
    audio_framework_source_config.data_interval_us = frame_duration_us * codec_frame_blocks_per_sdu;

    le_audio_source_hal_client_->Start(audio_framework_source_config, audioSinkReceiver, dsa_modes);

    /* We use same frame duration for sink/source */
    audio_framework_sink_config.data_interval_us = frame_duration_us * codec_frame_blocks_per_sdu;

    /* If group supports more than 16kHz for the microphone in converstional
     * case let's use that also for Audio Framework.
     */
    auto sink_configuration = group->GetAudioSessionCodecConfigForDirection(
            LeAudioContextType::CONVERSATIONAL,
            bluetooth::le_audio::types::kLeAudioDirectionSource);
    if (!sink_configuration.IsInvalid() &&
        sink_configuration.sample_rate > bluetooth::audio::le_audio::kSampleRate16000) {
      audio_framework_sink_config.sample_rate = sink_configuration.sample_rate;
    }

    le_audio_sink_hal_client_->Start(audio_framework_sink_config, audioSourceReceiver, dsa_modes);
  }

  bool isOutputPreferenceLeAudio(const RawAddress& address) {
    log::info("address: {}, active_group_id_: {}", address.ToStringForLogging(), active_group_id_);
    std::vector<RawAddress> active_leaudio_devices = GetGroupDevices(active_group_id_);
    if (std::find(active_leaudio_devices.begin(), active_leaudio_devices.end(), address) ==
        active_leaudio_devices.end()) {
      log::info("Device {} is not active for LE Audio", address.ToStringForLogging());
      return false;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
    log::info("active_group_id: {}, is_output_preference_le_audio_: {}", group->group_id_,
              group->is_output_preference_le_audio);
    return group->is_output_preference_le_audio;
  }

  bool isDuplexPreferenceLeAudio(const RawAddress& address) {
    log::info("address: {}, active_group_id_: {}", address.ToStringForLogging(), active_group_id_);
    std::vector<RawAddress> active_leaudio_devices = GetGroupDevices(active_group_id_);
    if (std::find(active_leaudio_devices.begin(), active_leaudio_devices.end(), address) ==
        active_leaudio_devices.end()) {
      log::info("Device {} is not active for LE Audio", address.ToStringForLogging());
      return false;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
    log::info("active_group_id: {}, is_duplex_preference_le_audio: {}", group->group_id_,
              group->is_duplex_preference_le_audio);
    return group->is_duplex_preference_le_audio;
  }

  void groupSetAndNotifyInactive(void) {
    if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
      return;
    }
    sink_monitor_notified_status_ = std::nullopt;
    source_monitor_notified_status_ = std::nullopt;
    log::info("Group id: {}", active_group_id_);

    StopSuspendTimeout();

    StopAudio();
    ClientAudioInterfaceRelease();

    callbacks_->OnGroupStatus(active_group_id_, GroupStatus::INACTIVE);
    active_group_id_ = bluetooth::groups::kGroupUnknown;
  }

  bool ConfigureStream(LeAudioDeviceGroup* group, bool up_to_qos_configured) {
    log::debug("group_id: {}", group->group_id_);

    BidirectionalPair<std::vector<uint8_t>> ccids = {
            .sink = ContentControlIdKeeper::GetInstance()->GetAllCcids(
                    local_metadata_context_types_.sink),
            .source = ContentControlIdKeeper::GetInstance()->GetAllCcids(
                    local_metadata_context_types_.source)};

    group->SetPendingConfiguration();
    if (!groupStateMachine_->ConfigureStream(group, configuration_context_type_,
                                             local_metadata_context_types_, ccids,
                                             up_to_qos_configured)) {
      group->ClearPendingConfiguration();
      return false;
    }

    return true;
  }

  void PrepareStreamForAConversational(LeAudioDeviceGroup* group) {
    if (!com::android::bluetooth::flags::leaudio_improve_switch_during_phone_call()) {
      log::info("Flag leaudio_improve_switch_during_phone_call is not enabled");
      return;
    }

    log::debug("group_id: {}", group->group_id_);

    auto remote_direction = bluetooth::le_audio::types::kLeAudioDirectionSink;
    ReconfigureOrUpdateRemote(group, remote_direction);

    if (configuration_context_type_ != LeAudioContextType::CONVERSATIONAL) {
      log::error("Something went wrong {} != {} ", ToString(configuration_context_type_),
                 ToString(LeAudioContextType::CONVERSATIONAL));
      return;
    }

    if (!ConfigureStream(group, true)) {
      log::info("Reconfiguration is needed for group {}", group->group_id_);
      initReconfiguration(group, LeAudioContextType::UNSPECIFIED);
    }
  }

  void GroupSetActive(const int group_id) override {
    log::info("group_id: {}", group_id);

    if (group_id == bluetooth::groups::kGroupUnknown) {
      if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
        callbacks_->OnGroupStatus(group_id, GroupStatus::INACTIVE);
        /* Nothing to do */
        return;
      }

      log::info("Active group_id changed {} -> {}", active_group_id_, group_id);
      auto group_id_to_close = active_group_id_;
      groupSetAndNotifyInactive();
      GroupStop(group_id_to_close);

      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    if (!group) {
      log::error("Invalid group: {}", static_cast<int>(group_id));
      callbacks_->OnGroupStatus(group_id, GroupStatus::INACTIVE);
      return;
    }

    if (group->NumOfConnected() == 0) {
      log::error("Group: {} is not connected anymore", static_cast<int>(group_id));
      callbacks_->OnGroupStatus(group_id, GroupStatus::INACTIVE);
      return;
    }

    if (active_group_id_ != bluetooth::groups::kGroupUnknown) {
      if (active_group_id_ == group_id) {
        log::info("Group is already active: {}", static_cast<int>(active_group_id_));
        callbacks_->OnGroupStatus(active_group_id_, GroupStatus::ACTIVE);
        return;
      }
      log::info("switching active group to: {}", group_id);

      auto result = CodecManager::GetInstance()->UpdateActiveUnicastAudioHalClient(
              le_audio_source_hal_client_.get(), le_audio_sink_hal_client_.get(), false);
      log::assert_that(result, "Could not update session to codec manager");
    }

    if (!le_audio_source_hal_client_) {
      le_audio_source_hal_client_ = LeAudioSourceAudioHalClient::AcquireUnicast();
      if (!le_audio_source_hal_client_) {
        log::error("could not acquire audio source interface");
        callbacks_->OnGroupStatus(group_id, GroupStatus::INACTIVE);
        return;
      }
    }

    if (!le_audio_sink_hal_client_) {
      le_audio_sink_hal_client_ = LeAudioSinkAudioHalClient::AcquireUnicast();
      if (!le_audio_sink_hal_client_) {
        log::error("could not acquire audio sink interface");
        callbacks_->OnGroupStatus(group_id, GroupStatus::INACTIVE);
        return;
      }
    }

    auto result = CodecManager::GetInstance()->UpdateActiveUnicastAudioHalClient(
            le_audio_source_hal_client_.get(), le_audio_sink_hal_client_.get(), true);
    log::assert_that(result, "Could not update session to codec manager");

    /* Mini policy: Try configure audio HAL sessions with most recent context.
     * If reconfiguration is not needed it means, context type is not supported.
     * If most recent scenario is not supported, try to find first supported.
     */
    LeAudioContextType default_context_type = configuration_context_type_;
    if (!group->IsAudioSetConfigurationAvailable(default_context_type)) {
      if (group->IsAudioSetConfigurationAvailable(LeAudioContextType::UNSPECIFIED)) {
        default_context_type = LeAudioContextType::UNSPECIFIED;
        default_context_type = LeAudioContextType::UNSPECIFIED;
      } else {
        for (LeAudioContextType context_type : kLeAudioContextAllTypesArray) {
          if (group->IsAudioSetConfigurationAvailable(context_type)) {
            default_context_type = context_type;
            break;
          }
        }
      }
    }

    /* Only update the configuration audio context and audio coding session
     * parameters if needed.
     */
    UpdateConfigAndCheckIfReconfigurationIsNeeded(group, default_context_type);

    auto previous_active_group = active_group_id_;
    log::info("Active group_id changed {} -> {}", previous_active_group, group_id);

    bool prepare_for_a_call = IsInVoipOrRegularCall();

    if (previous_active_group == bluetooth::groups::kGroupUnknown) {
      /* Expose audio sessions if there was no previous active group */
      StartAudioSession(group);
      active_group_id_ = group_id;

      /* For the fresh activated LeAudio device, do configuration ahead only when
       * phone is in a call.
       */
      if (prepare_for_a_call) {
        PrepareStreamForAConversational(group);
      }

    } else {
      /* In case there was an active group. Stop the stream, but before that, set
       * the new group so the group change is correctly handled in OnStateMachineStatusReportCb
       */
      active_group_id_ = group_id;
      SuspendedForReconfiguration();
      GroupStop(previous_active_group);
      /* Note: On purpose we are not sending INACTIVE status up to Java, because previous active
       * group will be provided in ACTIVE status. This is in order to have single call to audio
       * framework
       * If group become active while phone call, let's configure it right away up to
       * the QoS configured state so when audio framework resumes the stream,
       * only Enable will left.
       * Otherwise, if there is group switch, let's move ASEs to Configured state.
       */

      if (!ConfigureStream(group, prepare_for_a_call)) {
        log::info("Could not configure group {}", group->group_id_);
      }
    }

    /* Reset sink and source listener notified status */
    sink_monitor_notified_status_ = std::nullopt;
    source_monitor_notified_status_ = std::nullopt;
    if (com::android::bluetooth::flags::leaudio_codec_config_callback_order_fix()) {
      SendAudioGroupSelectableCodecConfigChanged(group);
      SendAudioGroupCurrentCodecConfigChanged(group);
      callbacks_->OnGroupStatus(active_group_id_, GroupStatus::ACTIVE);
    } else {
      callbacks_->OnGroupStatus(active_group_id_, GroupStatus::ACTIVE);
      SendAudioGroupSelectableCodecConfigChanged(group);
    }
  }

  void SetEnableState(const RawAddress& address, bool enabled) override {
    log::info("{}: {}", address, enabled ? "enabled" : "disabled");
    auto leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (leAudioDevice == nullptr) {
      log::warn("{} is null", address);
      return;
    }

    auto group_id = leAudioDevice->group_id_;
    auto group = aseGroups_.FindById(group_id);
    if (group == nullptr) {
      log::warn("Group {} is not available", group_id);
      return;
    }

    if (enabled) {
      group->Enable(gatt_if_, reconnection_mode_);
    } else {
      group->Disable(gatt_if_);
    }
  }

  void RemoveDevice(const RawAddress& address) override {
    log::info(": {}", address);
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      return;
    }

    /* Remove device from the background connect if it is there */
    BTA_GATTC_CancelOpen(gatt_if_, address, false);
    btif_storage_set_leaudio_autoconnect(address, false);

    log::info("{}, state: {}", address,
              bluetooth::common::ToString(leAudioDevice->GetConnectionState()));
    auto connection_state = leAudioDevice->GetConnectionState();
    switch (connection_state) {
      case DeviceConnectState::REMOVING:
        /* Just return, and let device disconnect */
        return;
      case DeviceConnectState::CONNECTED:
      case DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY:
      case DeviceConnectState::CONNECTED_BY_USER_GETTING_READY:
        /* ACL exist in this case, disconnect and mark as removing */
        Disconnect(address);
        [[fallthrough]];
      case DeviceConnectState::DISCONNECTING:
      case DeviceConnectState::DISCONNECTING_AND_RECOVER:
        /* Device is disconnecting, just mark it shall be removed after all. */
        leAudioDevice->SetConnectionState(DeviceConnectState::REMOVING);
        return;
      case DeviceConnectState::CONNECTING_AUTOCONNECT:
        /* Fallthrough as for AUTOCONNECT it might be that device is doing direct connect
         * in case of previous connection timeout.
         */
      case DeviceConnectState::CONNECTING_BY_USER:
        BTA_GATTC_CancelOpen(gatt_if_, address, true);
        leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTED);
        break;
      case DeviceConnectState::DISCONNECTED:
        /* Do nothing, just remove device  */
        break;
    }

    /* Remove the group assignment if not yet removed. It might happen that the
     * group module has already called the appropriate callback and we have
     * already removed the group assignment.
     */
    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      auto group = aseGroups_.FindById(leAudioDevice->group_id_);
      group_remove_node(group, address, true);
    }

    leAudioDevices_.Remove(address);
  }

  void Connect(const RawAddress& address) override {
    log::info(": {}", address);

    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      if (!BTM_IsBonded(address, BT_TRANSPORT_LE)) {
        log::error("Connecting  {} when not bonded", address);
        callbacks_->OnConnectionState(ConnectionState::DISCONNECTED, address);
        return;
      }
      leAudioDevices_.Add(address, DeviceConnectState::CONNECTING_BY_USER);
    } else {
      auto current_connect_state = leAudioDevice->GetConnectionState();
      if ((current_connect_state == DeviceConnectState::CONNECTED) ||
          (current_connect_state == DeviceConnectState::CONNECTING_BY_USER)) {
        log::error("Device {} is in invalid state: {}", leAudioDevice->address_,
                   bluetooth::common::ToString(current_connect_state));

        return;
      }

      if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
        auto group = GetGroupIfEnabled(leAudioDevice->group_id_);
        if (!group) {
          log::warn("{}, trying to connect to disabled group id {}", address,
                    leAudioDevice->group_id_);
          callbacks_->OnConnectionState(ConnectionState::DISCONNECTED, address);
          return;
        }
      }

      leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTING_BY_USER);

      bluetooth::le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
              leAudioDevice->group_id_, address, ConnectionState::CONNECTING,
              bluetooth::le_audio::ConnectionStatus::SUCCESS);
    }

    BTA_GATTC_Open(gatt_if_, address, BTM_BLE_DIRECT_CONNECTION, false);
  }

  std::vector<RawAddress> GetGroupDevices(const int group_id) override {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    std::vector<RawAddress> all_group_device_addrs;

    if (group != nullptr) {
      LeAudioDevice* leAudioDevice = group->GetFirstDevice();
      while (leAudioDevice) {
        all_group_device_addrs.push_back(leAudioDevice->address_);
        leAudioDevice = group->GetNextDevice(leAudioDevice);
      };
    }

    return all_group_device_addrs;
  }

  /* Restore paired device from storage to recreate groups */
  void AddFromStorage(const RawAddress& address, bool autoconnect,
                      std::optional<int> sink_audio_location,
                      std::optional<int> source_audio_location, int sink_supported_context_types,
                      int source_supported_context_types, const std::vector<uint8_t>& handles,
                      const std::vector<uint8_t>& sink_pacs,
                      const std::vector<uint8_t>& source_pacs, const std::vector<uint8_t>& ases,
                      const std::vector<uint8_t>& gmap) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);

    if (leAudioDevice) {
      log::error("Device is already loaded. Nothing to do.");
      return;
    }

    log::info(
            "restoring: {}, autoconnect {}, sink_audio_location: {}, "
            "source_audio_location: {}, sink_supported_context_types : 0x{:04x}, "
            "source_supported_context_types 0x{:04x}",
            address, autoconnect,
            sink_audio_location ? std::to_string(sink_audio_location.value()) : "none",
            source_audio_location ? std::to_string(source_audio_location.value()) : "none",
            sink_supported_context_types, source_supported_context_types);

    leAudioDevices_.Add(address, DeviceConnectState::DISCONNECTED);
    leAudioDevice = leAudioDevices_.FindByAddress(address);

    int group_id =
            DeviceGroups::Get()->GetGroupId(address, bluetooth::le_audio::uuid::kCapServiceUuid);
    if (group_id != bluetooth::groups::kGroupUnknown) {
      group_add_node(group_id, address);
    }

    BidirectionalPair<AudioContexts> supported_contexts = {
            .sink = AudioContexts(sink_supported_context_types),
            .source = AudioContexts(source_supported_context_types),
    };

    leAudioDevice->SetSupportedContexts(supported_contexts);

    /* Use same as supported ones for now. */
    leAudioDevice->SetAvailableContexts(supported_contexts);

    if (!DeserializeHandles(leAudioDevice, handles)) {
      log::warn("Could not load Handles");
    }

    if (sink_audio_location) {
      leAudioDevice->audio_locations_.sink->value = sink_audio_location.value();
    }

    if (source_audio_location) {
      leAudioDevice->audio_locations_.source->value = source_audio_location.value();
    }

    /* Presence of PAC characteristic for a direction means support for that direction */
    if (leAudioDevice->audio_locations_.source) {
      leAudioDevice->audio_directions_ |= bluetooth::le_audio::types::kLeAudioDirectionSource;
    }
    if (leAudioDevice->audio_locations_.sink) {
      leAudioDevice->audio_directions_ |= bluetooth::le_audio::types::kLeAudioDirectionSink;
      callbacks_->OnSinkAudioLocationAvailable(leAudioDevice->address_,
                                               leAudioDevice->audio_locations_.sink->value);
    }

    if (!DeserializeSinkPacs(leAudioDevice, sink_pacs)) {
      /* If PACs are invalid, just say whole cache is invalid */
      leAudioDevice->known_service_handles_ = false;
      log::warn("Could not load sink pacs");
    }

    if (!DeserializeSourcePacs(leAudioDevice, source_pacs)) {
      /* If PACs are invalid, just say whole cache is invalid */
      leAudioDevice->known_service_handles_ = false;
      log::warn("Could not load source pacs");
    }

    if (!DeserializeAses(leAudioDevice, ases)) {
      /* If ASEs are invalid, just say whole cache is invalid */
      leAudioDevice->known_service_handles_ = false;
      log::warn("Could not load ases");
    }

    if (gmap.size() != 0) {
      leAudioDevice->gmap_client_ = std::make_unique<GmapClient>(leAudioDevice->address_);
      if (!le_audio::DeserializeGmap(leAudioDevice->gmap_client_.get(), gmap)) {
        leAudioDevice->gmap_client_.reset();
        log::warn("Invalid GMAP storage for {}", leAudioDevice->address_);
      }
    }

    leAudioDevice->autoconnect_flag_ = autoconnect;
    /* When adding from storage, make sure that autoconnect is used
     * by all the devices in the group.
     */
    leAudioDevices_.SetInitialGroupAutoconnectState(group_id, gatt_if_, reconnection_mode_,
                                                    autoconnect);
  }

  bool GetHandlesForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(addr);
    return SerializeHandles(leAudioDevice, out);
  }

  bool GetGmapForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(addr);
    return SerializeGmap(leAudioDevice->gmap_client_.get(), out);
  }

  bool GetSinkPacsForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(addr);
    return SerializeSinkPacs(leAudioDevice, out);
  }

  bool GetSourcePacsForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(addr);
    return SerializeSourcePacs(leAudioDevice, out);
  }

  bool GetAsesForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(addr);

    return SerializeAses(leAudioDevice, out);
  }

  void BackgroundConnectIfNeeded(LeAudioDevice* leAudioDevice) {
    if (!leAudioDevice->autoconnect_flag_) {
      log::debug("Device {} not in the background connect", leAudioDevice->address_);
      return;
    }
    AddToBackgroundConnectCheckGroupConnected(leAudioDevice);
  }

  void Disconnect(const RawAddress& address) override {
    log::info(": {}", address);
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);

    if (!leAudioDevice) {
      log::warn("leAudioDevice not connected ( {} )", address);
      callbacks_->OnConnectionState(ConnectionState::DISCONNECTED, address);
      return;
    }

    auto connection_state = leAudioDevice->GetConnectionState();
    log::info("{}, state: {}", address, bluetooth::common::ToString(connection_state));

    switch (connection_state) {
      case DeviceConnectState::CONNECTING_BY_USER:
        /* Timeout happen on the Java layer. Device probably not in the range.
         * Cancel just direct connection and keep background if it is there.
         */
        BTA_GATTC_CancelOpen(gatt_if_, address, true);
        /* If this is a device which is a part of the group which is connected,
         * lets start backgroup connect
         */
        BackgroundConnectIfNeeded(leAudioDevice);
        return;
      case DeviceConnectState::CONNECTED: {
        /* User is disconnecting the device, we shall remove the autoconnect
         * flag for this device and all others if not TA is used
         */
        /* If target announcement is used, do not remove autoconnect
         */
        bool remove_from_autoconnect =
                (reconnection_mode_ != BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS);

        if (leAudioDevice->autoconnect_flag_ && remove_from_autoconnect) {
          log::info("Removing autoconnect flag for group_id {}", leAudioDevice->group_id_);

          /* Removes device from background connect */
          BTA_GATTC_CancelOpen(gatt_if_, address, false);
          btif_storage_set_leaudio_autoconnect(address, false);
          leAudioDevice->autoconnect_flag_ = false;
        }

        /* Make sure ACL is disconnected to avoid reconnecting immediately
         * when autoconnect with TA reconnection mechanism is used.
         */
        bool force_acl_disconnect = leAudioDevice->autoconnect_flag_;

        auto group = aseGroups_.FindById(leAudioDevice->group_id_);
        if (group) {
          /* Remove devices from auto connect mode */
          for (auto dev = group->GetFirstDevice(); dev; dev = group->GetNextDevice(dev)) {
            if (remove_from_autoconnect &&
                (dev->GetConnectionState() == DeviceConnectState::CONNECTING_AUTOCONNECT)) {
              btif_storage_set_leaudio_autoconnect(dev->address_, false);
              dev->autoconnect_flag_ = false;
              BTA_GATTC_CancelOpen(gatt_if_, dev->address_, false);
              dev->SetConnectionState(DeviceConnectState::DISCONNECTED);
            }
          }

          /* If group is Streaming or is in transition for Streaming - lets stop it
           * and mark device to disconnect when stream is closed
           */
          if (group->IsStreaming() || !group->IsReleasingOrIdle()) {
            log::debug("group_id {} needs to stop streaming before {} disconnection",
                       group->group_id_, leAudioDevice->address_);
            leAudioDevice->closing_stream_for_disconnection_ = true;
            groupStateMachine_->StopStream(group);
            return;
          }

          if (group->IsReleasing()) {
            log::debug("group_id {} needs to stop streaming before {} disconnection",
                       group->group_id_, leAudioDevice->address_);
            /* Stream is releasing, wait till it is completed and then disconnect ACL. */
            leAudioDevice->closing_stream_for_disconnection_ = true;
            return;
          }

          force_acl_disconnect &= group->IsEnabled();
        }

        DisconnectDevice(leAudioDevice, force_acl_disconnect);
      }
        return;
      case DeviceConnectState::CONNECTED_BY_USER_GETTING_READY:
        /* Timeout happen on the Java layer before native got ready with the
         * device */
        DisconnectDevice(leAudioDevice);
        return;
      case DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY:
        /* Java is not aware about autoconnect actions,
         * therefore this should not happen.
         */
        log::warn("Should not happen - disconnect device");
        DisconnectDevice(leAudioDevice);
        return;
      case DeviceConnectState::DISCONNECTED:
      case DeviceConnectState::DISCONNECTING:
      case DeviceConnectState::DISCONNECTING_AND_RECOVER:
      case DeviceConnectState::CONNECTING_AUTOCONNECT:
      case DeviceConnectState::REMOVING:
        log::warn("{}, invalid state {}", address, bluetooth::common::ToString(connection_state));
        return;
    }
  }

  void DisconnectDevice(LeAudioDevice* leAudioDevice, bool acl_force_disconnect = false,
                        bool recover = false) {
    if (leAudioDevice->conn_id_ == GATT_INVALID_CONN_ID) {
      return;
    }

    if (leAudioDevice->GetConnectionState() != DeviceConnectState::REMOVING) {
      leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTING);
    }

    BtaGattQueue::Clean(leAudioDevice->conn_id_);

    /* Remote in bad state, force ACL Disconnection. */
    if (acl_force_disconnect) {
      leAudioDevice->DisconnectAcl();
      if (recover) {
        leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTING_AND_RECOVER);
      }
    } else {
      BTA_GATTC_Close(leAudioDevice->conn_id_);
    }
  }

  void DeregisterNotifications(LeAudioDevice* leAudioDevice) {
    /* GATTC will omit not registered previously handles */
    for (auto pac_tuple : leAudioDevice->snk_pacs_) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           std::get<0>(pac_tuple).val_hdl);
    }
    for (auto pac_tuple : leAudioDevice->src_pacs_) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           std::get<0>(pac_tuple).val_hdl);
    }

    if (leAudioDevice->audio_locations_.sink) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           leAudioDevice->audio_locations_.sink->handles.val_hdl);
    }
    if (leAudioDevice->audio_locations_.source) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           leAudioDevice->audio_locations_.source->handles.val_hdl);
    }
    if (leAudioDevice->audio_avail_hdls_.val_hdl != 0) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           leAudioDevice->audio_avail_hdls_.val_hdl);
    }
    if (leAudioDevice->audio_supp_cont_hdls_.val_hdl != 0) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           leAudioDevice->audio_supp_cont_hdls_.val_hdl);
    }
    if (leAudioDevice->ctp_hdls_.val_hdl != 0) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_,
                                           leAudioDevice->ctp_hdls_.val_hdl);
    }

    for (struct ase& ase : leAudioDevice->ases_) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_, ase.hdls.val_hdl);
    }
  }

  void handleInitialCtpCccRead(LeAudioDevice* leAudioDevice, uint16_t len, uint8_t* value) {
    if (len != 2) {
      log::error("Could not read CCC for {}, disconnecting", leAudioDevice->address_);
      instance->Disconnect(leAudioDevice->address_);
      return;
    }

    uint16_t val = *(uint16_t*)value;
    if (val == 0) {
      log::warn("{} forgot CCC values. Re-subscribing", leAudioDevice->address_);
      RegisterKnownNotifications(leAudioDevice, false, true);
      return;
    }

    log::verbose("{}, ASCS ctp ccc: {:#x}", leAudioDevice->address_, val);
    connectionReady(leAudioDevice);
  }

  /* This is a generic read/notify/indicate handler for gatt. Here messages
   * are dispatched to correct elements e.g. ASEs, PACs, audio locations etc.
   */
  void LeAudioCharValueHandle(tCONN_ID conn_id, uint16_t hdl, uint16_t len, uint8_t* value,
                              bool notify = false) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByConnId(conn_id);
    struct ase* ase;

    if (!leAudioDevice) {
      log::error("no leAudioDevice assigned to connection id: {}", conn_id);
      return;
    }

    ase = leAudioDevice->GetAseByValHandle(hdl);
    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);
    if (ase) {
      groupStateMachine_->ProcessGattNotifEvent(value, len, ase, leAudioDevice, group);
      return;
    }

    /* Initial CCC read to check if remote device properly keeps CCC values */
    if (hdl == leAudioDevice->ctp_hdls_.ccc_hdl) {
      handleInitialCtpCccRead(leAudioDevice, len, value);
      return;
    }

    auto snk_pac_ent =
            std::find_if(leAudioDevice->snk_pacs_.begin(), leAudioDevice->snk_pacs_.end(),
                         [&hdl](auto& pac_ent) { return std::get<0>(pac_ent).val_hdl == hdl; });
    if (snk_pac_ent != leAudioDevice->snk_pacs_.end()) {
      std::vector<struct bluetooth::le_audio::types::acs_ac_record> pac_recs;

      /* Guard consistency of PAC records structure */
      if (!bluetooth::le_audio::client_parser::pacs::ParsePacs(pac_recs, len, value)) {
        log::error("Sink PACs corrupted");
        return;
      }

      log::info("Registering sink PACs");
      leAudioDevice->RegisterPACs(&std::get<1>(*snk_pac_ent), &pac_recs);

      /* Cached audio set configurations should be considered invalid when
       * PACs are updated.
       */
      if (group) {
        /* Changes in PAC record channel counts may change the strategy */
        group->InvalidateGroupStrategy();
        group->InvalidateCachedConfigurations();
      }
      if (notify) {
        btif_storage_leaudio_update_pacs_bin(leAudioDevice->address_);
      }
      return;
    }

    auto src_pac_ent =
            std::find_if(leAudioDevice->src_pacs_.begin(), leAudioDevice->src_pacs_.end(),
                         [&hdl](auto& pac_ent) { return std::get<0>(pac_ent).val_hdl == hdl; });
    if (src_pac_ent != leAudioDevice->src_pacs_.end()) {
      std::vector<struct bluetooth::le_audio::types::acs_ac_record> pac_recs;

      /* Guard consistency of PAC records structure */
      if (!bluetooth::le_audio::client_parser::pacs::ParsePacs(pac_recs, len, value)) {
        log::error("Source PACs corrupted");
        return;
      }

      log::info("Registering source PACs");
      leAudioDevice->RegisterPACs(&std::get<1>(*src_pac_ent), &pac_recs);

      /* Cached audio set configurations should be considered invalid when
       * PACs are updated.
       */
      if (group) {
        /* Changes in PAC record channel counts may change the strategy */
        group->InvalidateGroupStrategy();
        group->InvalidateCachedConfigurations();
      }
      if (notify) {
        btif_storage_leaudio_update_pacs_bin(leAudioDevice->address_);
      }
      return;
    }

    if (leAudioDevice->audio_locations_.sink &&
        hdl == leAudioDevice->audio_locations_.sink->handles.val_hdl) {
      AudioLocations snk_audio_locations;
      bluetooth::le_audio::client_parser::pacs::ParseAudioLocations(snk_audio_locations, len,
                                                                    value);

      /* Value may not change */
      if (!leAudioDevice->audio_locations_.sink ||
          (leAudioDevice->audio_locations_.sink->value ^ snk_audio_locations).none()) {
        return;
      }

      /* Presence of PAC characteristic for source means support for source
       * audio location. Value of 0x00000000 means mono/unspecified
       */
      leAudioDevice->audio_directions_ |= bluetooth::le_audio::types::kLeAudioDirectionSink;
      leAudioDevice->audio_locations_.sink->value = snk_audio_locations;

      callbacks_->OnSinkAudioLocationAvailable(leAudioDevice->address_, snk_audio_locations);

      if (notify) {
        btif_storage_set_leaudio_sink_audio_location(
                leAudioDevice->address_, leAudioDevice->audio_locations_.sink->value.to_ulong());
        if (group && group->IsReleasingOrIdle()) {
          UpdateLocationsAndContextsAvailability(group);
        }
      }
    } else if (leAudioDevice->audio_locations_.source &&
               hdl == leAudioDevice->audio_locations_.source->handles.val_hdl) {
      AudioLocations src_audio_locations;
      bluetooth::le_audio::client_parser::pacs::ParseAudioLocations(src_audio_locations, len,
                                                                    value);

      /* Value may not change */
      if (!leAudioDevice->audio_locations_.source ||
          (leAudioDevice->audio_locations_.source->value ^ src_audio_locations).none()) {
        return;
      }

      /* Presence of PAC characteristic for source means support for source
       * audio location. Value of 0x00000000 means mono/unspecified
       */
      leAudioDevice->audio_directions_ |= bluetooth::le_audio::types::kLeAudioDirectionSource;
      leAudioDevice->audio_locations_.source->value = src_audio_locations;

      if (notify) {
        btif_storage_set_leaudio_source_audio_location(
                leAudioDevice->address_, leAudioDevice->audio_locations_.source->value.to_ulong());
        if (group && group->IsReleasingOrIdle()) {
          UpdateLocationsAndContextsAvailability(group);
        }
      }
    } else if (hdl == leAudioDevice->audio_avail_hdls_.val_hdl) {
      BidirectionalPair<AudioContexts> contexts;
      if (!bluetooth::le_audio::client_parser::pacs::ParseAvailableAudioContexts(contexts, len,
                                                                                 value)) {
        return;
      }

      AudioContexts current_group_contexts;

      if (group) {
        current_group_contexts = group->GetAvailableContexts();
      }

      leAudioDevice->SetAvailableContexts(contexts);

      if (!group) {
        return;
      }

      if (group->IsInTransition()) {
        /* Group is in transition.
         * if group is going to stream, schedule attaching the device to the
         * group.
         */

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          AttachToStreamingGroupIfNeeded(leAudioDevice);
        }
        return;
      }

      /* Whenever context type change, notify user about that.
       * Note: GetAvailableContexts() add streaming context as well
       */
      UpdateLocationsAndContextsAvailability(
              group, current_group_contexts != group->GetAvailableContexts());

      if (!group->IsStreaming()) {
        return;
      }

      AttachToStreamingGroupIfNeeded(leAudioDevice);

    } else if (hdl == leAudioDevice->audio_supp_cont_hdls_.val_hdl) {
      BidirectionalPair<AudioContexts> supp_audio_contexts;
      if (bluetooth::le_audio::client_parser::pacs::ParseSupportedAudioContexts(supp_audio_contexts,
                                                                                len, value)) {
        /* Just store if for now */
        leAudioDevice->SetSupportedContexts(supp_audio_contexts);

        btif_storage_set_leaudio_supported_context_types(leAudioDevice->address_,
                                                         supp_audio_contexts.sink.value(),
                                                         supp_audio_contexts.source.value());
      }
    } else if (hdl == leAudioDevice->ctp_hdls_.val_hdl) {
      groupStateMachine_->ProcessGattCtpNotification(group, leAudioDevice, value, len);
    } else if (hdl == leAudioDevice->tmap_role_hdl_) {
      bluetooth::le_audio::client_parser::tmap::ParseTmapRole(leAudioDevice->tmap_role_, len,
                                                              value);
    } else if (leAudioDevice->gmap_client_ != nullptr && GmapClient::IsGmapClientEnabled() &&
               hdl == leAudioDevice->gmap_client_->getRoleHandle()) {
      leAudioDevice->gmap_client_->parseAndSaveGmapRole(len, value);
      btif_storage_leaudio_update_gmap_bin(leAudioDevice->address_);
    } else if (leAudioDevice->gmap_client_ != nullptr && GmapClient::IsGmapClientEnabled() &&
               hdl == leAudioDevice->gmap_client_->getUGTFeatureHandle()) {
      leAudioDevice->gmap_client_->parseAndSaveUGTFeature(len, value);
      btif_storage_leaudio_update_gmap_bin(leAudioDevice->address_);
    } else {
      log::error("Unknown attribute read: 0x{:x}", hdl);
    }
  }

  void OnGattReadRsp(tCONN_ID conn_id, tGATT_STATUS /*status*/, uint16_t hdl, uint16_t len,
                     uint8_t* value, void* /*data*/) {
    LeAudioCharValueHandle(conn_id, hdl, len, value);
  }

  LeAudioDeviceGroup* GetGroupIfEnabled(int group_id) {
    auto group = aseGroups_.FindById(group_id);
    if (group == nullptr) {
      log::info("Group {} does not exist", group_id);
      return nullptr;
    }
    if (!group->IsEnabled()) {
      log::info("Group {} is disabled", group_id);
      return nullptr;
    }
    return group;
  }

  void AddToBackgroundConnectCheckGroupConnected(LeAudioDevice* leAudioDevice) {
    /* If device belongs to streaming group, add it on allow list */
    auto address = leAudioDevice->address_;
    auto group = GetGroupIfEnabled(leAudioDevice->group_id_);
    if (group == nullptr) {
      log::info("Group {} is invalid or disabled", leAudioDevice->group_id_);
      return;
    }

    leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTING_AUTOCONNECT);

    /* Cancel previous bakcground connect */
    BTA_GATTC_CancelOpen(gatt_if_, address, false);
    if (group->IsAnyDeviceConnected()) {
      log::info("Group {} in connected state. Adding {} to allow list", leAudioDevice->group_id_,
                address);
      BTA_GATTC_Open(gatt_if_, address, BTM_BLE_BKG_CONNECT_ALLOW_LIST, false);
    } else {
      log::info("Adding {} to background connect (default reconnection_mode (0x{:02x}))", address,
                reconnection_mode_);
      BTA_GATTC_Open(gatt_if_, address, reconnection_mode_, false);
    }
  }

  void OnGattConnected(tGATT_STATUS status, tCONN_ID conn_id, tGATT_IF /*client_if*/,
                       RawAddress address, tBT_TRANSPORT transport, uint16_t mtu) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);

    log::info("{}, conn_id=0x{:04x}, transport={}, status={} (0x{:02x})", address, conn_id,
              bt_transport_text(transport), gatt_status_text(status), status);

    if (transport != BT_TRANSPORT_LE) {
      log::warn("Only LE connection is allowed (transport {})", bt_transport_text(transport));
      BTA_GATTC_Close(conn_id);
      return;
    }

    if (!leAudioDevice) {
      return;
    }

    if (leAudioDevice->conn_id_ != GATT_INVALID_CONN_ID) {
      log::debug("Already connected {}, conn_id=0x{:04x}", address, leAudioDevice->conn_id_);
      return;
    }

    if (status != GATT_SUCCESS) {
      /* Clear current connection request and let it be set again if needed */
      BTA_GATTC_CancelOpen(gatt_if_, address, false);

      /* autoconnect connection failed, that's ok */
      if (status != GATT_ILLEGAL_PARAMETER &&
          (leAudioDevice->GetConnectionState() == DeviceConnectState::CONNECTING_AUTOCONNECT ||
           leAudioDevice->autoconnect_flag_)) {
        log::info("Device not available now, do background connect.");
        leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTED);
        AddToBackgroundConnectCheckGroupConnected(leAudioDevice);
        return;
      }

      leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTED);

      log::error("Failed to connect to LeAudio leAudioDevice, status: 0x{:02x}", status);
      callbacks_->OnConnectionState(ConnectionState::DISCONNECTED, address);
      bluetooth::le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
              leAudioDevice->group_id_, address, ConnectionState::CONNECTED,
              bluetooth::le_audio::ConnectionStatus::FAILED);
      return;
    }

    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      auto group = GetGroupIfEnabled(leAudioDevice->group_id_);
      if (group == nullptr) {
        BTA_GATTC_CancelOpen(gatt_if_, address, false);

        log::warn("LeAudio profile is disabled for group_id: {}. {} is not connected",
                  leAudioDevice->group_id_, address);
        return;
      }
    }

    leAudioDevice->conn_id_ = conn_id;
    leAudioDevice->mtu_ = mtu;
    if (com::android::bluetooth::flags::gatt_queue_cleanup_connected()) {
      BtaGattQueue::Clean(conn_id);
    }

    /* Remove device from the background connect (it might be either Allow list
     * or TA) and add it again with reconnection_mode_. In case it is TA, we are
     * sure that device will not be in the allow list for other applications
     * which are using background connect.
     */
    BTA_GATTC_CancelOpen(gatt_if_, address, false);
    BTA_GATTC_Open(gatt_if_, address, reconnection_mode_, false);

    if (bluetooth::shim::GetController()->SupportsBle2mPhy()) {
      log::info("{} set preferred PHY to 2M", address);
      get_btm_client_interface().ble.BTM_BleSetPhy(address, PHY_LE_2M, PHY_LE_2M, 0);
    }

    get_btm_client_interface().peer.BTM_RequestPeerSCA(leAudioDevice->address_, transport);

    if (leAudioDevice->GetConnectionState() == DeviceConnectState::CONNECTING_AUTOCONNECT) {
      leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY);
    } else {
      leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTED_BY_USER_GETTING_READY);
    }

    /* Check if the device is in allow list and update the flag */
    leAudioDevice->UpdateDeviceAllowlistFlag();
    if (BTM_SecIsLeSecurityPending(address)) {
      /* if security collision happened, wait for encryption done
       * (BTA_GATTC_ENC_CMPL_CB_EVT) */
      return;
    }

    /* verify bond */
    if (BTM_IsEncrypted(address, BT_TRANSPORT_LE)) {
      /* if link has been encrypted */
      OnEncryptionComplete(address, tBTM_STATUS::BTM_SUCCESS);
      return;
    }

    tBTM_STATUS result =
            BTM_SetEncryption(address, BT_TRANSPORT_LE, nullptr, nullptr, BTM_BLE_SEC_ENCRYPT);

    log::info("Encryption required for {}. Request result: 0x{:02x}", address, result);

    if (result == tBTM_STATUS::BTM_ERR_KEY_MISSING) {
      log::error("Link key unknown for {}, disconnect profile", address);
      bluetooth::le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
              leAudioDevice->group_id_, address, ConnectionState::CONNECTED,
              bluetooth::le_audio::ConnectionStatus::FAILED);

      /* If link cannot be enctypted, disconnect profile */
      BTA_GATTC_Close(conn_id);
    }
  }

  void RegisterKnownNotifications(LeAudioDevice* leAudioDevice, bool gatt_register,
                                  bool write_ccc) {
    log::info("device: {}", leAudioDevice->address_);

    if (leAudioDevice->ctp_hdls_.val_hdl == 0) {
      log::error("Control point characteristic is mandatory - disconnecting device {}",
                 leAudioDevice->address_);
      DisconnectDevice(leAudioDevice);
      return;
    }

    /* GATTC will omit not registered previously handles */
    for (auto pac_tuple : leAudioDevice->snk_pacs_) {
      subscribe_for_notification(leAudioDevice->conn_id_, leAudioDevice->address_,
                                 std::get<0>(pac_tuple), gatt_register, write_ccc);
    }
    for (auto pac_tuple : leAudioDevice->src_pacs_) {
      subscribe_for_notification(leAudioDevice->conn_id_, leAudioDevice->address_,
                                 std::get<0>(pac_tuple), gatt_register, write_ccc);
    }

    if (leAudioDevice->audio_locations_.sink) {
      subscribe_for_notification(leAudioDevice->conn_id_, leAudioDevice->address_,
                                 leAudioDevice->audio_locations_.sink->handles, gatt_register,
                                 write_ccc);
    }
    if (leAudioDevice->audio_locations_.source) {
      subscribe_for_notification(leAudioDevice->conn_id_, leAudioDevice->address_,
                                 leAudioDevice->audio_locations_.source->handles, gatt_register,
                                 write_ccc);
    }

    if (leAudioDevice->audio_avail_hdls_.val_hdl != 0) {
      subscribe_for_notification(leAudioDevice->conn_id_, leAudioDevice->address_,
                                 leAudioDevice->audio_avail_hdls_, gatt_register, write_ccc);
    }

    if (leAudioDevice->audio_supp_cont_hdls_.val_hdl != 0) {
      subscribe_for_notification(leAudioDevice->conn_id_, leAudioDevice->address_,
                                 leAudioDevice->audio_supp_cont_hdls_, gatt_register, write_ccc);
    }

    for (struct ase& ase : leAudioDevice->ases_) {
      subscribe_for_notification(leAudioDevice->conn_id_, leAudioDevice->address_, ase.hdls,
                                 gatt_register, write_ccc);
    }

    subscribe_for_notification(leAudioDevice->conn_id_, leAudioDevice->address_,
                               leAudioDevice->ctp_hdls_, gatt_register, write_ccc);
  }

  void changeMtuIfPossible(LeAudioDevice* leAudioDevice) {
    if (leAudioDevice->mtu_ == GATT_DEF_BLE_MTU_SIZE) {
      log::info("Configure MTU");
      /* Use here kBapMinimumAttMtu, because we know that GATT will request
       * default ATT MTU anyways. We also know that GATT will use this
       * kBapMinimumAttMtu as an input for Data Length Update procedure in the controller.
       */
      BtaGattQueue::ConfigureMtu(leAudioDevice->conn_id_, kBapMinimumAttMtu);
    }
  }

  void ReadMustHaveAttributesOnReconnect(LeAudioDevice* leAudioDevice) {
    bool is_eatt_supported = gatt_profile_get_eatt_support_by_conn_id(leAudioDevice->conn_id_);

    log::verbose("{}, eatt supported {}", leAudioDevice->address_, is_eatt_supported);
    /* Here we read
     * 1) ASCS Control Point CCC descriptor in order to validate proper
     *    behavior of remote device which should store CCC values for bonded device.
     * 2) Available Context Types which normally should be notified by the server,
     *    but since it is crucial for proper streaming experiance, and in the same time
     *    it can change very often which, as we observed, might lead to not being sent by
     *    remote devices
     */
    if (!com::android::bluetooth::flags::le_ase_read_multiple_variable() || !is_eatt_supported) {
      BtaGattQueue::ReadCharacteristic(leAudioDevice->conn_id_,
                                       leAudioDevice->audio_avail_hdls_.val_hdl,
                                       OnGattReadRspStatic, NULL);
      BtaGattQueue::ReadCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.ccc_hdl,
                                       OnGattReadRspStatic, NULL);
    } else {
      tBTA_GATTC_MULTI multi_read = {.num_attr = 2,
                                     .handles = {leAudioDevice->audio_avail_hdls_.val_hdl,
                                                 leAudioDevice->ctp_hdls_.ccc_hdl}};

      BtaGattQueue::ReadMultiCharacteristic(leAudioDevice->conn_id_, multi_read,
                                            OnGattReadMultiRspStatic, NULL);
    }
  }

  void OnEncryptionComplete(const RawAddress& address, tBTM_STATUS status) {
    log::info("{} status 0x{:02x}", address, status);
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (leAudioDevice == NULL || (leAudioDevice->conn_id_ == GATT_INVALID_CONN_ID)) {
      log::warn("Skipping device which is {}",
                leAudioDevice ? " not connected by service." : " null");
      return;
    }

    if (status != tBTM_STATUS::BTM_SUCCESS) {
      log::error("Encryption failed status: {}", btm_status_text(status));
      if (leAudioDevice->GetConnectionState() ==
          DeviceConnectState::CONNECTED_BY_USER_GETTING_READY) {
        callbacks_->OnConnectionState(ConnectionState::DISCONNECTED, address);
        bluetooth::le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
                leAudioDevice->group_id_, address, ConnectionState::CONNECTED,
                bluetooth::le_audio::ConnectionStatus::FAILED);
      }

      leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTING);

      BTA_GATTC_Close(leAudioDevice->conn_id_);
      return;
    }

    if (leAudioDevice->encrypted_) {
      log::info("link already encrypted, nothing to do");
      return;
    }

    /* If PHY update did not succeed after ACL connection, which can happen
     * when remote feature read was not that quick, lets try to change phy here
     * one more time
     */
    if (!leAudioDevice->acl_phy_update_done_ &&
        bluetooth::shim::GetController()->SupportsBle2mPhy()) {
      log::info("{} set preferred PHY to 2M", leAudioDevice->address_);
      get_btm_client_interface().ble.BTM_BleSetPhy(address, PHY_LE_2M, PHY_LE_2M, 0);
    }

    changeMtuIfPossible(leAudioDevice);

    leAudioDevice->encrypted_ = true;

    /* If we know services, register for notifications */
    if (leAudioDevice->known_service_handles_) {
      /* This registration will do subscribtion in local GATT as we
       * assume remote device keeps bonded CCC values.
       */
      RegisterKnownNotifications(leAudioDevice, true, false);
      ReadMustHaveAttributesOnReconnect(leAudioDevice);
    }

    /* If we know services and read is not ongoing, this is reconnection and
     * just notify connected  */
    if (leAudioDevice->known_service_handles_ && !leAudioDevice->notify_connected_after_read_) {
      log::info("Wait for CCC registration and MTU change request");
      return;
    }

    BTA_GATTC_ServiceSearchRequest(leAudioDevice->conn_id_,
                                   bluetooth::le_audio::uuid::kPublishedAudioCapabilityServiceUuid);
  }

  void checkGroupConnectionStateAfterMemberDisconnect(int group_id) {
    /* This is fired t=kGroupConnectedWatchDelayMs after group member
     * got disconnected while either group members were connected.
     * We want to check here if there is any group member connected.
     * If so we should add other group members to allow list for better
     * reconnection experience. If  all group members are disconnected
     * i e.g. devices intentionally disconnected for other
     * purposes like pairing with other device, then we do nothing here and
     * device stay on the default reconnection policy (i.e. targeted
     * announcements)
     */
    auto group = aseGroups_.FindById(group_id);
    if (group == nullptr) {
      log::info("Group {} is destroyed.", group_id);
      return;
    }

    if (!group->IsAnyDeviceConnected()) {
      log::info("Group {} is not connected", group_id);
      /* Make sure all devices are in the default reconnection mode */
      group->ApplyReconnectionMode(gatt_if_, reconnection_mode_);
      return;
    }

    /* if group is still connected, make sure that other not connected
     * set members are in the allow list for the quick reconnect.
     * E.g. for the earbud case, probably one of the earbud is in the case now.
     */
    group->AddToAllowListNotConnectedGroupMembers(gatt_if_);
  }

  void scheduleGroupConnectedCheck(int group_id) {
    log::info("Schedule group_id {} connected check.", group_id);
    do_in_main_thread_delayed(
            base::BindOnce(&LeAudioClientImpl::checkGroupConnectionStateAfterMemberDisconnect,
                           weak_factory_.GetWeakPtr(), group_id),
            std::chrono::milliseconds(kGroupConnectedWatchDelayMs));
  }

  void autoConnect(RawAddress address) {
    auto leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (leAudioDevice == nullptr) {
      log::warn("Device {} not valid anymore", address);
      return;
    }

    BackgroundConnectIfNeeded(leAudioDevice);
  }

  void scheduleAutoConnect(RawAddress& address) {
    log::info("Schedule auto connect {}", address);
    do_in_main_thread_delayed(
            base::BindOnce(&LeAudioClientImpl::autoConnect, weak_factory_.GetWeakPtr(), address),
            std::chrono::milliseconds(kAutoConnectAfterOwnDisconnectDelayMs));
  }

  void recoveryReconnect(RawAddress address) {
    log::info("Reconnecting to {} after timeout on state machine.", address);
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);

    if (leAudioDevice == nullptr ||
        leAudioDevice->GetConnectionState() != DeviceConnectState::DISCONNECTING_AND_RECOVER) {
      log::warn("Device {}, not interested in recovery connect anymore", address);
      return;
    }

    auto group = GetGroupIfEnabled(leAudioDevice->group_id_);

    if (group != nullptr) {
      leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTING_AUTOCONNECT);
      BTA_GATTC_Open(gatt_if_, address, BTM_BLE_DIRECT_CONNECTION, false);
    } else {
      leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTED);
    }
  }

  void scheduleRecoveryReconnect(RawAddress& address) {
    log::info("Schedule reconnecting to {} after timeout on state machine.", address);
    do_in_main_thread_delayed(base::BindOnce(&LeAudioClientImpl::recoveryReconnect,
                                             weak_factory_.GetWeakPtr(), address),
                              std::chrono::milliseconds(kRecoveryReconnectDelayMs));
  }

  void checkIfGroupMember(RawAddress address) {
    log::info("checking being a group member: {}", address);
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);

    if (leAudioDevice == nullptr) {
      log::warn("Device {}, probably removed", address);
      return;
    }

    if (leAudioDevice->group_id_ == bluetooth::groups::kGroupUnknown) {
      disconnectInvalidDevice(leAudioDevice, ", device not a valid group member",
                              LeAudioHealthDeviceStatType::INVALID_CSIS);
      return;
    }
  }

  /* This is called, when CSIS native module is about to add device to the
   * group once the CSIS service will be verified on the remote side.
   * After some time (kCsisGroupMemberDelayMs)  a checkIfGroupMember will be
   * called and will verify if the remote device has a group_id properly set.
   * if not, it means there is something wrong with CSIS service on the remote
   * side.
   */
  void scheduleGuardForCsisAdd(RawAddress& address) {
    log::info("Schedule reconnecting to {} after timeout on state machine.", address);
    do_in_main_thread_delayed(base::BindOnce(&LeAudioClientImpl::checkIfGroupMember,
                                             weak_factory_.GetWeakPtr(), address),
                              std::chrono::milliseconds(kCsisGroupMemberDelayMs));
  }

  void OnGattDisconnected(tCONN_ID conn_id, tGATT_IF /*client_if*/, RawAddress address,
                          tGATT_DISCONN_REASON reason) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByConnId(conn_id);

    if (!leAudioDevice) {
      log::error(", skipping unknown leAudioDevice, address: {}", address);
      return;
    }

    leAudioDevice->acl_asymmetric_ = false;
    BtaGattQueue::Clean(leAudioDevice->conn_id_);
    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

    DeregisterNotifications(leAudioDevice);

    callbacks_->OnConnectionState(ConnectionState::DISCONNECTED, address);
    leAudioDevice->conn_id_ = GATT_INVALID_CONN_ID;
    leAudioDevice->mtu_ = 0;
    leAudioDevice->closing_stream_for_disconnection_ = false;
    leAudioDevice->encrypted_ = false;
    leAudioDevice->acl_phy_update_done_ = false;

    auto connection_state = leAudioDevice->GetConnectionState();

    leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTED);

    groupStateMachine_->ProcessHciNotifAclDisconnected(group, leAudioDevice);

    bluetooth::le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
            leAudioDevice->group_id_, address, ConnectionState::DISCONNECTED,
            bluetooth::le_audio::ConnectionStatus::SUCCESS);

    if (connection_state == DeviceConnectState::REMOVING) {
      if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
        auto group = aseGroups_.FindById(leAudioDevice->group_id_);
        group_remove_node(group, address, true);
      }
      leAudioDevices_.Remove(address);
      return;
    }

    log::info("{}, autoconnect {}, reason 0x{:02x}, connection state {}", leAudioDevice->address_,
              leAudioDevice->autoconnect_flag_, reason,
              bluetooth::common::ToString(connection_state));

    if (connection_state == DeviceConnectState::DISCONNECTING_AND_RECOVER) {
      /* We are back after disconnecting device which was in a bad state.
       * lets try to reconnected - 30 sec with direct connect and later fallback
       * to default background reconnection mode.
       * Since GATT notifies us before ACL was dropped, let's wait a bit
       * before we do reconnect.
       *
       * Also, make sure that device has state which allows to do recover
       */
      leAudioDevice->SetConnectionState(DeviceConnectState::DISCONNECTING_AND_RECOVER);
      scheduleRecoveryReconnect(address);
      return;
    }

    /* Attempt background re-connect if disconnect was not initiated locally
     * or if autoconnect is set and device got disconnected because of some
     * issues
     */
    if (group == nullptr || !group->IsEnabled()) {
      log::error("Group id {} ({}) disabled or null", leAudioDevice->group_id_,
                 std::format_ptr(group));
      return;
    }

    if (reason == GATT_CONN_TERMINATE_LOCAL_HOST) {
      if (leAudioDevice->autoconnect_flag_) {
        /* In this case ACL might not yet been disconnected */
        scheduleAutoConnect(address);
      }
      return;
    }

    /* Remote disconnects from us or Timeout happens */
    /* In this case ACL is disconnected */
    if (reason == GATT_CONN_TIMEOUT) {
      leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTING_AUTOCONNECT);

      /* If timeout try to reconnect for 30 sec.*/
      BTA_GATTC_Open(gatt_if_, address, BTM_BLE_DIRECT_CONNECTION, false);
      return;
    }

    /* In other disconnect resons we act based on the autoconnect_flag_ */
    if (leAudioDevice->autoconnect_flag_) {
      if (group->IsAnyDeviceConnected()) {
        /* If all set is disconnecting, let's give it some time.
         * If not all get disconnected, and there will be group member
         * connected we want to put disconnected devices to allow list
         */
        scheduleGroupConnectedCheck(leAudioDevice->group_id_);
      } else {
        group->ApplyReconnectionMode(gatt_if_, reconnection_mode_);
      }
    }
  }

  bool subscribe_for_notification(tCONN_ID conn_id, const RawAddress& address,
                                  const struct bluetooth::le_audio::types::hdl_pair& handle_pair,
                                  bool gatt_register = true, bool write_ccc = true) {
    std::vector<uint8_t> value(2);
    uint8_t* ptr = value.data();
    uint16_t handle = handle_pair.val_hdl;
    uint16_t ccc_handle = handle_pair.ccc_hdl;

    log::info("conn id {}, gatt_register: {}, write_ccc: {}", conn_id, gatt_register, write_ccc);
    if (gatt_register &&
        BTA_GATTC_RegisterForNotifications(gatt_if_, address, handle) != GATT_SUCCESS) {
      log::error("cannot register for notification: {}", static_cast<int>(handle));
      return false;
    }

    if (write_ccc == false) {
      log::verbose("CCC is not written to {} (0x{:04x}), handle 0x{:04x}", address, conn_id,
                   ccc_handle);
      return true;
    }

    UINT16_TO_STREAM(ptr, GATT_CHAR_CLIENT_CONFIG_NOTIFICATION);

    BtaGattQueue::WriteDescriptor(
            conn_id, ccc_handle, std::move(value), GATT_WRITE,
            [](tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t /*len*/,
               const uint8_t* /*value*/, void* data) {
              if (instance) {
                instance->OnGattWriteCcc(conn_id, status, handle, data);
              }
            },
            nullptr);
    return true;
  }

  /* Find the handle for the client characteristics configuration of a given
   * characteristics.
   */
  uint16_t find_ccc_handle(const gatt::Characteristic& charac) {
    auto iter = std::find_if(charac.descriptors.begin(), charac.descriptors.end(),
                             [](const auto& desc) {
                               return desc.uuid == Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG);
                             });

    return iter == charac.descriptors.end() ? 0 : (*iter).handle;
  }

  void ClearDeviceInformationAndStartSearch(LeAudioDevice* leAudioDevice) {
    if (!leAudioDevice) {
      log::warn("leAudioDevice is null");
      return;
    }

    log::info("{}", leAudioDevice->address_);

    if (leAudioDevice->known_service_handles_ == false) {
      log::debug("Database already invalidated");
      return;
    }

    leAudioDevice->known_service_handles_ = false;
    leAudioDevice->csis_member_ = false;
    BtaGattQueue::Clean(leAudioDevice->conn_id_);
    DeregisterNotifications(leAudioDevice);

    if (leAudioDevice->GetConnectionState() == DeviceConnectState::CONNECTED) {
      leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTED_BY_USER_GETTING_READY);
    }

    btif_storage_leaudio_clear_service_data(leAudioDevice->address_);

    BTA_GATTC_ServiceSearchRequest(leAudioDevice->conn_id_,
                                   bluetooth::le_audio::uuid::kPublishedAudioCapabilityServiceUuid);
  }

  void OnServiceChangeEvent(const RawAddress& address) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice) {
      log::warn("Skipping unknown leAudioDevice {} ({})", address, std::format_ptr(leAudioDevice));
      return;
    }

    if (leAudioDevice->conn_id_ != GATT_INVALID_CONN_ID) {
      ClearDeviceInformationAndStartSearch(leAudioDevice);
      return;
    }

    /* If device is not connected, just clear the handle information and this
     * will trigger service search onGattConnected */
    leAudioDevice->known_service_handles_ = false;
    btif_storage_leaudio_clear_service_data(address);
  }

  void OnMtuChanged(tCONN_ID conn_id, uint16_t mtu) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByConnId(conn_id);
    if (!leAudioDevice) {
      log::debug("Unknown connectect id {}", conn_id);
      return;
    }

    /**
     * BAP 1.01. 3.6.1
     * ATT and EATT transport requirements
     * The Unicast Client shall support a minimum ATT_MTU of 64 octets for one
     * Unenhanced ATT bearer, or for at least one Enhanced ATT bearer if the
     * Unicast Client supports Enhanced ATT bearers.
     *
     */
    if (mtu < 64) {
      log::error("Device {} MTU is too low ({}). Disconnecting from LE Audio",
                 leAudioDevice->address_, mtu);
      Disconnect(leAudioDevice->address_);
      return;
    }

    leAudioDevice->mtu_ = mtu;
  }

  void OnPhyUpdate(tCONN_ID conn_id, uint8_t tx_phy, uint8_t rx_phy, tGATT_STATUS status) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByConnId(conn_id);
    if (leAudioDevice == nullptr) {
      log::debug("Unknown conn_id {:#x}", conn_id);
      return;
    }

    log::info("{}, tx_phy: {:#x}, rx_phy: {:#x} , status: {:#x}", leAudioDevice->address_, tx_phy,
              rx_phy, status);

    if (status == 0) {
      leAudioDevice->acl_phy_update_done_ = true;
    }
  }

  void OnGattServiceDiscoveryDone(const RawAddress& address) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(address);
    if (!leAudioDevice || (leAudioDevice->conn_id_ == GATT_INVALID_CONN_ID)) {
      log::verbose("skipping unknown leAudioDevice, address {} ({})", address,
                   std::format_ptr(leAudioDevice));
      return;
    }

    if (!leAudioDevice->encrypted_) {
      log::debug("Wait for device to be encrypted");
      return;
    }

    if (!leAudioDevice->known_service_handles_) {
      BTA_GATTC_ServiceSearchRequest(
              leAudioDevice->conn_id_,
              bluetooth::le_audio::uuid::kPublishedAudioCapabilityServiceUuid);
    }
  }

  void disconnectInvalidDevice(LeAudioDevice* leAudioDevice, std::string error_string,
                               LeAudioHealthDeviceStatType stat) {
    log::error("{}, {}", leAudioDevice->address_, error_string);
    if (leAudioHealthStatus_) {
      leAudioHealthStatus_->AddStatisticForDevice(leAudioDevice, stat);
    }
    DisconnectDevice(leAudioDevice);
  }

  /* This method is called after connection beginning to identify and initialize
   * a le audio device. Any missing mandatory attribute will result in reverting
   * and cleaning up device.
   */
  void OnServiceSearchComplete(tCONN_ID conn_id, tGATT_STATUS status) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByConnId(conn_id);

    if (!leAudioDevice) {
      log::error("skipping unknown leAudioDevice, conn_id: 0x{:x}", conn_id);
      return;
    }

    log::info("test csis_member {}", leAudioDevice->csis_member_);

    if (status != GATT_SUCCESS) {
      /* close connection and report service discovery complete with error */
      log::error("Service discovery failed");

      DisconnectDevice(leAudioDevice);
      return;
    }

    if (!leAudioDevice->encrypted_) {
      log::warn("Device not yet bonded - waiting for encryption");
      return;
    }

    const std::list<gatt::Service>* services = BTA_GATTC_GetServices(conn_id);

    const gatt::Service* pac_svc = nullptr;
    const gatt::Service* ase_svc = nullptr;
    const gatt::Service* tmas_svc = nullptr;
    const gatt::Service* gmap_svc = nullptr;

    std::vector<uint16_t> csis_primary_handles;
    uint16_t cas_csis_included_handle = 0;

    for (const gatt::Service& tmp : *services) {
      if (tmp.uuid == bluetooth::le_audio::uuid::kPublishedAudioCapabilityServiceUuid) {
        log::info("Found Audio Capability service, handle: 0x{:04x}, device: {}", tmp.handle,
                  leAudioDevice->address_);
        pac_svc = &tmp;
      } else if (tmp.uuid == bluetooth::le_audio::uuid::kAudioStreamControlServiceUuid) {
        log::info("Found Audio Stream Endpoint service, handle: 0x{:04x}, device: {}", tmp.handle,
                  leAudioDevice->address_);
        ase_svc = &tmp;
      } else if (tmp.uuid == bluetooth::csis::kCsisServiceUuid) {
        log::info("Found CSIS service, handle: 0x{:04x}, is primary: {}, device: {}", tmp.handle,
                  tmp.is_primary, leAudioDevice->address_);
        if (tmp.is_primary) {
          csis_primary_handles.push_back(tmp.handle);
        }
      } else if (tmp.uuid == bluetooth::le_audio::uuid::kCapServiceUuid) {
        log::info("Found CAP service, handle: 0x{:04x}, device: {}", tmp.handle,
                  leAudioDevice->address_);

        /* Try to find context for CSIS instances */
        for (auto& included_srvc : tmp.included_services) {
          if (included_srvc.uuid == bluetooth::csis::kCsisServiceUuid) {
            log::info("CSIS included into CAS");
            if (bluetooth::csis::CsisClient::IsCsisClientRunning()) {
              cas_csis_included_handle = included_srvc.start_handle;
            }

            break;
          }
        }
      } else if (tmp.uuid == bluetooth::le_audio::uuid::kTelephonyMediaAudioServiceUuid) {
        log::info("Found Telephony and Media Audio service, handle: 0x{:04x}, device: {}",
                  tmp.handle, leAudioDevice->address_);
        tmas_svc = &tmp;
      } else if (tmp.uuid == bluetooth::le_audio::uuid::kGamingAudioServiceUuid) {
        log::info("Found Gaming Audio service, handle: 0x{:04x}, device: {}", tmp.handle,
                  leAudioDevice->address_);
        gmap_svc = &tmp;
      }
    }

    /* Check if CAS includes primary CSIS service */
    if (!csis_primary_handles.empty() && cas_csis_included_handle) {
      auto iter = std::find(csis_primary_handles.begin(), csis_primary_handles.end(),
                            cas_csis_included_handle);
      if (iter != csis_primary_handles.end()) {
        leAudioDevice->csis_member_ = true;
      }
    }

    if (!pac_svc || !ase_svc) {
      disconnectInvalidDevice(leAudioDevice, "No mandatory le audio services found (pacs or ascs)",
                              LeAudioHealthDeviceStatType::INVALID_DB);
      return;
    }

    /* Refresh PACs handles */
    leAudioDevice->ClearPACs();

    for (const gatt::Characteristic& charac : pac_svc->characteristics) {
      if (charac.uuid ==
          bluetooth::le_audio::uuid::kSinkPublishedAudioCapabilityCharacteristicUuid) {
        struct hdl_pair hdl_pair;
        hdl_pair.val_hdl = charac.value_handle;
        hdl_pair.ccc_hdl = find_ccc_handle(charac);

        if (hdl_pair.ccc_hdl == 0) {
          log::info(", Sink PACs ccc not available");
        }

        if (hdl_pair.ccc_hdl != 0 &&
            !subscribe_for_notification(conn_id, leAudioDevice->address_, hdl_pair)) {
          disconnectInvalidDevice(leAudioDevice, ", could not subscribe for snk pac char",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        /* Obtain initial state of sink PACs */
        BtaGattQueue::ReadCharacteristic(conn_id, hdl_pair.val_hdl, OnGattReadRspStatic, NULL);

        leAudioDevice->snk_pacs_.push_back(std::make_tuple(
                hdl_pair, std::vector<struct bluetooth::le_audio::types::acs_ac_record>()));

        log::info("Found Sink PAC characteristic, handle: 0x{:04x}, ccc handle: 0x{:04x}, addr: {}",
                  charac.value_handle, hdl_pair.ccc_hdl, leAudioDevice->address_);
      } else if (charac.uuid ==
                 bluetooth::le_audio::uuid::kSourcePublishedAudioCapabilityCharacteristicUuid) {
        struct hdl_pair hdl_pair;
        hdl_pair.val_hdl = charac.value_handle;
        hdl_pair.ccc_hdl = find_ccc_handle(charac);

        if (hdl_pair.ccc_hdl == 0) {
          log::info(", Source PACs ccc not available");
        }

        if (hdl_pair.ccc_hdl != 0 &&
            !subscribe_for_notification(conn_id, leAudioDevice->address_, hdl_pair)) {
          disconnectInvalidDevice(leAudioDevice, ", could not subscribe for src pac char",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        /* Obtain initial state of source PACs */
        BtaGattQueue::ReadCharacteristic(conn_id, hdl_pair.val_hdl, OnGattReadRspStatic, NULL);

        leAudioDevice->src_pacs_.push_back(std::make_tuple(
                hdl_pair, std::vector<struct bluetooth::le_audio::types::acs_ac_record>()));

        log::info(
                "Found Source PAC characteristic, handle: 0x{:04x}, ccc handle: 0x{:04x}, addr: {}",
                charac.value_handle, hdl_pair.ccc_hdl, leAudioDevice->address_);
      } else if (charac.uuid == bluetooth::le_audio::uuid::kSinkAudioLocationCharacteristicUuid) {
        auto ccc_hdl = find_ccc_handle(charac);
        leAudioDevice->audio_locations_.sink.emplace(hdl_pair(charac.value_handle, ccc_hdl),
                                                     AudioLocations(0));

        if (ccc_hdl == 0) {
          log::info(", snk audio locations char doesn't have ccc");
        } else if (!subscribe_for_notification(conn_id, leAudioDevice->address_,
                                               leAudioDevice->audio_locations_.sink->handles)) {
          disconnectInvalidDevice(leAudioDevice, ", could not subscribe for snk locations char",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        /* Obtain initial state of sink audio locations */
        BtaGattQueue::ReadCharacteristic(conn_id,
                                         leAudioDevice->audio_locations_.sink->handles.val_hdl,
                                         OnGattReadRspStatic, NULL);

        log::info(
                "Found Sink audio locations characteristic, handle: 0x{:04x}, ccc handle: "
                "0x{:04x}, addr: {}",
                charac.value_handle, ccc_hdl, leAudioDevice->address_);
      } else if (charac.uuid == bluetooth::le_audio::uuid::kSourceAudioLocationCharacteristicUuid) {
        auto ccc_hdl = find_ccc_handle(charac);
        leAudioDevice->audio_locations_.source.emplace(hdl_pair(charac.value_handle, ccc_hdl),
                                                       AudioLocations(0));

        if (ccc_hdl == 0) {
          log::info(", src audio locations char doesn't have ccc");
        } else if (!subscribe_for_notification(conn_id, leAudioDevice->address_,
                                               leAudioDevice->audio_locations_.source->handles)) {
          disconnectInvalidDevice(leAudioDevice, ", could not subscribe for src locations char",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        /* Obtain initial state of source audio locations */
        BtaGattQueue::ReadCharacteristic(conn_id,
                                         leAudioDevice->audio_locations_.source->handles.val_hdl,
                                         OnGattReadRspStatic, NULL);

        log::info(
                "Found Source audio locations characteristic, handle: 0x{:04x}, ccc handle: "
                "0x{:04x}, addr: {}",
                charac.value_handle, ccc_hdl, leAudioDevice->address_);
      } else if (charac.uuid ==
                 bluetooth::le_audio::uuid::kAudioContextAvailabilityCharacteristicUuid) {
        leAudioDevice->audio_avail_hdls_.val_hdl = charac.value_handle;
        leAudioDevice->audio_avail_hdls_.ccc_hdl = find_ccc_handle(charac);

        if (leAudioDevice->audio_avail_hdls_.ccc_hdl == 0) {
          disconnectInvalidDevice(leAudioDevice, ", audio avails char doesn't have ccc",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        if (!subscribe_for_notification(conn_id, leAudioDevice->address_,
                                        leAudioDevice->audio_avail_hdls_)) {
          disconnectInvalidDevice(leAudioDevice, ", could not subscribe for audio avails char",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        /* Obtain initial state */
        BtaGattQueue::ReadCharacteristic(conn_id, leAudioDevice->audio_avail_hdls_.val_hdl,
                                         OnGattReadRspStatic, NULL);

        log::info(
                "Found Audio Availability Context characteristic, handle: "
                "0x{:04x}, ccc handle: 0x{:04x}, addr: {}",
                charac.value_handle, leAudioDevice->audio_avail_hdls_.ccc_hdl,
                leAudioDevice->address_);
      } else if (charac.uuid ==
                 bluetooth::le_audio::uuid::kAudioSupportedContextCharacteristicUuid) {
        leAudioDevice->audio_supp_cont_hdls_.val_hdl = charac.value_handle;
        leAudioDevice->audio_supp_cont_hdls_.ccc_hdl = find_ccc_handle(charac);

        if (leAudioDevice->audio_supp_cont_hdls_.ccc_hdl == 0) {
          log::info(", audio supported char doesn't have ccc");
        }

        if (leAudioDevice->audio_supp_cont_hdls_.ccc_hdl != 0 &&
            !subscribe_for_notification(conn_id, leAudioDevice->address_,
                                        leAudioDevice->audio_supp_cont_hdls_)) {
          disconnectInvalidDevice(leAudioDevice,
                                  ", could not subscribe for audio supported ctx char",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        /* Obtain initial state */
        BtaGattQueue::ReadCharacteristic(conn_id, leAudioDevice->audio_supp_cont_hdls_.val_hdl,
                                         OnGattReadRspStatic, NULL);

        log::info(
                "Found Audio Supported Context characteristic, handle: 0x{:04x}, "
                "ccc handle: 0x{:04x}, addr: {}",
                charac.value_handle, leAudioDevice->audio_supp_cont_hdls_.ccc_hdl,
                leAudioDevice->address_);
      }
    }

    /* Refresh ASE handles */
    leAudioDevice->ases_.clear();

    for (const gatt::Characteristic& charac : ase_svc->characteristics) {
      log::info("Found characteristic, uuid: {}", charac.uuid.ToString());
      if (charac.uuid == bluetooth::le_audio::uuid::kSinkAudioStreamEndpointUuid ||
          charac.uuid == bluetooth::le_audio::uuid::kSourceAudioStreamEndpointUuid) {
        uint16_t ccc_handle = find_ccc_handle(charac);
        if (ccc_handle == 0) {
          disconnectInvalidDevice(leAudioDevice, ", ASE char doesn't have ccc",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }
        struct bluetooth::le_audio::types::hdl_pair hdls(charac.value_handle, ccc_handle);
        if (!subscribe_for_notification(conn_id, leAudioDevice->address_, hdls)) {
          disconnectInvalidDevice(leAudioDevice, ", could not subscribe ASE char",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        int direction = charac.uuid == bluetooth::le_audio::uuid::kSinkAudioStreamEndpointUuid
                                ? bluetooth::le_audio::types::kLeAudioDirectionSink
                                : bluetooth::le_audio::types::kLeAudioDirectionSource;

        leAudioDevice->ases_.emplace_back(charac.value_handle, ccc_handle, direction);

        log::info(
                "Found ASE characteristic, handle: 0x{:04x}, ccc handle: 0x{:04x}, "
                "direction: {}, addr: {}",
                charac.value_handle, ccc_handle, direction, leAudioDevice->address_);
      } else if (charac.uuid ==
                 bluetooth::le_audio::uuid::kAudioStreamEndpointControlPointCharacteristicUuid) {
        leAudioDevice->ctp_hdls_.val_hdl = charac.value_handle;
        leAudioDevice->ctp_hdls_.ccc_hdl = find_ccc_handle(charac);

        if (leAudioDevice->ctp_hdls_.ccc_hdl == 0) {
          disconnectInvalidDevice(leAudioDevice, ", ASE ctp doesn't have ccc",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        if (!subscribe_for_notification(conn_id, leAudioDevice->address_,
                                        leAudioDevice->ctp_hdls_)) {
          disconnectInvalidDevice(leAudioDevice, ", could not subscribe ASE char",
                                  LeAudioHealthDeviceStatType::INVALID_DB);
          return;
        }

        log::info(
                "Found ASE Control Point characteristic, handle: 0x{:04x}, ccc "
                "handle: 0x{:04x}, addr: {}",
                charac.value_handle, leAudioDevice->ctp_hdls_.ccc_hdl, leAudioDevice->address_);
      }
    }

    if (tmas_svc) {
      for (const gatt::Characteristic& charac : tmas_svc->characteristics) {
        if (charac.uuid ==
            bluetooth::le_audio::uuid::kTelephonyMediaAudioProfileRoleCharacteristicUuid) {
          leAudioDevice->tmap_role_hdl_ = charac.value_handle;

          /* Obtain initial state of TMAP role */
          BtaGattQueue::ReadCharacteristic(conn_id, leAudioDevice->tmap_role_hdl_,
                                           OnGattReadRspStatic, NULL);

          log::info(
                  "Found Telephony and Media Profile characteristic, handle: 0x{:04x}, device: {}",
                  leAudioDevice->tmap_role_hdl_, leAudioDevice->address_);
        }
      }
    }

    if (gmap_svc && GmapClient::IsGmapClientEnabled()) {
      leAudioDevice->gmap_client_ = std::make_unique<GmapClient>(leAudioDevice->address_);
      for (const gatt::Characteristic& charac : gmap_svc->characteristics) {
        if (charac.uuid == bluetooth::le_audio::uuid::kRoleCharacteristicUuid) {
          uint16_t handle = charac.value_handle;
          leAudioDevice->gmap_client_->setRoleHandle(handle);
          BtaGattQueue::ReadCharacteristic(conn_id, handle, OnGattReadRspStatic, NULL);
          log::info("Found Gmap Role characteristic, handle: 0x{:04x}, device: {}",
                    leAudioDevice->gmap_client_->getRoleHandle(), leAudioDevice->address_);
        }
        if (charac.uuid == bluetooth::le_audio::uuid::kUnicastGameTerminalCharacteristicUuid) {
          uint16_t handle = charac.value_handle;
          leAudioDevice->gmap_client_->setUGTFeatureHandle(handle);
          BtaGattQueue::ReadCharacteristic(conn_id, handle, OnGattReadRspStatic, NULL);
          log::info("Found Gmap UGT Feature characteristic, handle: 0x{:04x}, device: {}",
                    leAudioDevice->gmap_client_->getUGTFeatureHandle(), leAudioDevice->address_);
        }
      }
    }

    leAudioDevice->known_service_handles_ = true;
    leAudioDevice->notify_connected_after_read_ = true;
    if (leAudioHealthStatus_) {
      leAudioHealthStatus_->AddStatisticForDevice(leAudioDevice,
                                                  LeAudioHealthDeviceStatType::VALID_DB);
    }

    /* If already known group id */
    if (leAudioDevice->group_id_ != bluetooth::groups::kGroupUnknown) {
      AseInitialStateReadRequest(leAudioDevice);
      return;
    }

    /* If device does not belong to any group yet we either add it to the
     * group by our self now or wait for Csis to do it. In both cases, let's
     * check if group is already assigned.
     */
    int group_id = DeviceGroups::Get()->GetGroupId(leAudioDevice->address_,
                                                   bluetooth::le_audio::uuid::kCapServiceUuid);
    if (group_id != bluetooth::groups::kGroupUnknown) {
      instance->group_add_node(group_id, leAudioDevice->address_);
      return;
    }

    /* CSIS will trigger adding to group */
    if (leAudioDevice->csis_member_) {
      log::info("{},  waiting for CSIS to create group for device", leAudioDevice->address_);
      scheduleGuardForCsisAdd(leAudioDevice->address_);
      return;
    }

    log::info("{} Not a CSIS member. Create group by our own", leAudioDevice->address_);

    /* If there is no Csis just add device by our own */
    DeviceGroups::Get()->AddDevice(leAudioDevice->address_,
                                   bluetooth::le_audio::uuid::kCapServiceUuid);
  }

  void OnGattWriteCcc(tCONN_ID conn_id, tGATT_STATUS status, uint16_t hdl, void* /*data*/) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByConnId(conn_id);
    std::vector<struct ase>::iterator ase_it;

    if (!leAudioDevice) {
      log::error("unknown conn_id=0x{:x}", conn_id);
      return;
    }

    if (status == GATT_DATABASE_OUT_OF_SYNC) {
      log::info("Database out of sync for {}, conn_id: 0x{:04x}", leAudioDevice->address_, conn_id);
      ClearDeviceInformationAndStartSearch(leAudioDevice);
      return;
    }

    if (status == GATT_SUCCESS) {
      log::info("Successfully registered on ccc: 0x{:04x}, device: {}", hdl,
                leAudioDevice->address_);

      if (leAudioDevice->ctp_hdls_.ccc_hdl == hdl && leAudioDevice->known_service_handles_ &&
          !leAudioDevice->notify_connected_after_read_) {
        /* Reconnection case. Control point is the last CCC LeAudio is
         * registering for on reconnection */
        connectionReady(leAudioDevice);
      }

      return;
    }

    log::error("Failed to register for notifications: 0x{:04x}, device: {}, status: 0x{:02x}", hdl,
               leAudioDevice->address_, status);

    ase_it =
            std::find_if(leAudioDevice->ases_.begin(), leAudioDevice->ases_.end(),
                         [&hdl](const struct ase& ase) -> bool { return ase.hdls.ccc_hdl == hdl; });

    if (ase_it == leAudioDevice->ases_.end()) {
      log::error("Unknown ccc handle: 0x{:04x}, device: {}", hdl, leAudioDevice->address_);
      return;
    }

    BTA_GATTC_DeregisterForNotifications(gatt_if_, leAudioDevice->address_, ase_it->hdls.val_hdl);
  }

  void AttachToStreamingGroupIfNeeded(LeAudioDevice* leAudioDevice) {
    if (leAudioDevice->group_id_ != active_group_id_) {
      log::info("group  {} is not streaming. Nothing to do", leAudioDevice->group_id_);
      return;
    }

    if (leAudioDevice->GetConnectionState() != DeviceConnectState::CONNECTED) {
      /* Do nothing, wait until device is connected */
      log::debug("{} is not yet connected", leAudioDevice->address_);
      return;
    }

    if (leAudioDevice->HaveActiveAse()) {
      log::debug("{} is already configured, nothing to do", leAudioDevice->address_);
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);

    auto group_metadata_contexts = get_bidirectional(group->GetMetadataContexts());
    auto device_available_contexts = leAudioDevice->GetAvailableContexts();
    if (!group_metadata_contexts.test_any(device_available_contexts)) {
      log::info(
              "{} does not have required context type. Group Context type: {}, device available {}",
              leAudioDevice->address_, common::ToString(group_metadata_contexts),
              common::ToString(device_available_contexts));
      return;
    }

    /* Restore configuration */
    auto* stream_conf = &group->stream_conf;

    if (audio_sender_state_ == AudioState::IDLE && audio_receiver_state_ == AudioState::IDLE) {
      log::debug("Device not streaming but active - nothing to do");
      return;
    }

    if (!stream_conf->conf) {
      log::info("Configuration not yet set. Nothing to do now");
      return;
    }

    log::info("Attaching {} to group: {}", leAudioDevice->address_, leAudioDevice->group_id_);

    for (auto direction : {bluetooth::le_audio::types::kLeAudioDirectionSink,
                           bluetooth::le_audio::types::kLeAudioDirectionSource}) {
      log::info("Looking for requirements: {} - {}", stream_conf->conf->name,
                (direction == 1 ? "snk" : "src"));
      const auto& pacs = (direction == bluetooth::le_audio::types::kLeAudioDirectionSink)
                                 ? leAudioDevice->snk_pacs_
                                 : leAudioDevice->src_pacs_;
      for (const auto& ent : stream_conf->conf->confs.get(direction)) {
        if (!bluetooth::le_audio::utils::GetConfigurationSupportedPac(pacs, ent.codec)) {
          log::info("Configuration is not supported by device {}", leAudioDevice->address_);

          /* Reconfigure if newly connected member device cannot support
           * current codec configuration */
          initReconfiguration(group, configuration_context_type_);
          return;
        }
      }
    }

    /* Do not put the TBS CCID when not using Telecom for the VoIP calls. */
    auto ccid_contexts = group->GetMetadataContexts();
    if (IsInVoipCall() && !IsInCall()) {
      ccid_contexts.sink.unset(LeAudioContextType::CONVERSATIONAL);
      ccid_contexts.source.unset(LeAudioContextType::CONVERSATIONAL);
    }
    BidirectionalPair<std::vector<uint8_t>> ccids = {
            .sink = ContentControlIdKeeper::GetInstance()->GetAllCcids(ccid_contexts.sink),
            .source = ContentControlIdKeeper::GetInstance()->GetAllCcids(ccid_contexts.source)};

    if (!groupStateMachine_->AttachToStream(group, leAudioDevice, std::move(ccids))) {
      log::warn("Could not add device {} to the group {} streaming.", leAudioDevice->address_,
                group->group_id_);
      scheduleAttachDeviceToTheStream(leAudioDevice->address_);
    } else {
      speed_start_setup(group->group_id_, configuration_context_type_, 1);
    }
  }

  void restartAttachToTheStream(const RawAddress& addr) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByAddress(addr);
    if (leAudioDevice == nullptr || leAudioDevice->conn_id_ == GATT_INVALID_CONN_ID) {
      log::info("Device {} not available anymore", addr);
      return;
    }
    AttachToStreamingGroupIfNeeded(leAudioDevice);
  }

  void scheduleAttachDeviceToTheStream(const RawAddress& addr) {
    log::info("Device {} is scheduled for streaming", addr);
    do_in_main_thread_delayed(base::BindOnce(&LeAudioClientImpl::restartAttachToTheStream,
                                             weak_factory_.GetWeakPtr(), addr),
                              std::chrono::milliseconds(kDeviceAttachDelayMs));
  }

  void SendAudioGroupSelectableCodecConfigChanged(LeAudioDeviceGroup* group) {
    auto leAudioDevice = group->GetFirstDevice();
    callbacks_->OnAudioGroupSelectableCodecConf(
            group->group_id_,
            bluetooth::le_audio::utils::GetRemoteBtLeAudioCodecConfigFromPac(
                    leAudioDevice->src_pacs_),
            bluetooth::le_audio::utils::GetRemoteBtLeAudioCodecConfigFromPac(
                    leAudioDevice->snk_pacs_));
  }

  void SendAudioGroupCurrentCodecConfigChanged(LeAudioDeviceGroup* group) {
    // This shall be called when configuration changes
    log::debug("{}", group->group_id_);

    auto audio_set_conf = group->GetConfiguration(configuration_context_type_);
    if (!audio_set_conf) {
      log::warn("Stream configuration is not valid for group id {}", group->group_id_);
      return;
    }

    bluetooth::le_audio::btle_audio_codec_config_t input_config{};
    bluetooth::le_audio::utils::fillStreamParamsToBtLeAudioCodecConfig(audio_set_conf->confs.source,
                                                                       input_config);

    bluetooth::le_audio::btle_audio_codec_config_t output_config{};
    bluetooth::le_audio::utils::fillStreamParamsToBtLeAudioCodecConfig(audio_set_conf->confs.sink,
                                                                       output_config);

    callbacks_->OnAudioGroupCurrentCodecConf(group->group_id_, input_config, output_config);
  }

  void connectionReady(LeAudioDevice* leAudioDevice) {
    log::debug("{},  {}", leAudioDevice->address_,
               bluetooth::common::ToString(leAudioDevice->GetConnectionState()));

    stack::l2cap::get_interface().L2CA_LockBleConnParamsForProfileConnection(
            leAudioDevice->address_, false);

    if (leAudioDevice->GetConnectionState() ==
                DeviceConnectState::CONNECTED_BY_USER_GETTING_READY &&
        (leAudioDevice->autoconnect_flag_ == false)) {
      btif_storage_set_leaudio_autoconnect(leAudioDevice->address_, true);
      leAudioDevice->autoconnect_flag_ = true;
    }

    leAudioDevice->SetConnectionState(DeviceConnectState::CONNECTED);
    bluetooth::le_audio::MetricsCollector::Get()->OnConnectionStateChanged(
            leAudioDevice->group_id_, leAudioDevice->address_, ConnectionState::CONNECTED,
            bluetooth::le_audio::ConnectionStatus::SUCCESS);

    if (leAudioDevice->group_id_ == bluetooth::groups::kGroupUnknown) {
      log::warn("LeAudio device {} connected with no group", leAudioDevice->address_);
      callbacks_->OnConnectionState(ConnectionState::CONNECTED, leAudioDevice->address_);
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);
    if (group) {
      UpdateLocationsAndContextsAvailability(group, true);
    }

    /* Notify connected after contexts are notified */
    callbacks_->OnConnectionState(ConnectionState::CONNECTED, leAudioDevice->address_);

    AttachToStreamingGroupIfNeeded(leAudioDevice);

    if (reconnection_mode_ == BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS) {
      /* Add other devices to allow list if there are any not yet connected
       * from the group
       */
      group->AddToAllowListNotConnectedGroupMembers(gatt_if_);
    }
  }

  bool IsAseAcceptingAudioData(struct ase* ase) {
    if (ase == nullptr) {
      return false;
    }
    if (ase->state != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      return false;
    }
    if (ase->data_path_state != DataPathState::CONFIGURED) {
      return false;
    }

    return true;
  }

  // mix stero signal into mono
  std::vector<uint8_t> mono_blend(const std::vector<uint8_t>& buf, int bytes_per_sample,
                                  size_t frames) {
    std::vector<uint8_t> mono_out;
    mono_out.resize(frames * bytes_per_sample);

    if (bytes_per_sample == 2) {
      int16_t* out = (int16_t*)mono_out.data();
      const int16_t* in = (int16_t*)(buf.data());
      for (size_t i = 0; i < frames; ++i) {
        int accum = 0;
        accum += *in++;
        accum += *in++;
        accum /= 2;  // round to 0
        *out++ = accum;
      }
    } else if (bytes_per_sample == 4) {
      int32_t* out = (int32_t*)mono_out.data();
      const int32_t* in = (int32_t*)(buf.data());
      for (size_t i = 0; i < frames; ++i) {
        int accum = 0;
        accum += *in++;
        accum += *in++;
        accum /= 2;  // round to 0
        *out++ = accum;
      }
    } else {
      log::error("Don't know how to mono blend that {}!", bytes_per_sample);
    }
    return mono_out;
  }

  void PrepareAndSendToTwoCises(
          const std::vector<uint8_t>& data,
          const struct bluetooth::le_audio::stream_parameters& stream_params) {
    uint16_t left_cis_handle = 0;
    uint16_t right_cis_handle = 0;

    uint16_t number_of_required_samples_per_channel = sw_enc_left->GetNumOfSamplesPerChannel();
    uint8_t bytes_per_sample = sw_enc_left->GetNumOfBytesPerSample();
    if (data.size() <
        bytes_per_sample * 2 /* channels */ * number_of_required_samples_per_channel) {
      log::error("Missing samples. Data size: {} expected: {}", data.size(),
                 bytes_per_sample * 2 * number_of_required_samples_per_channel);
      return;
    }

    for (auto const& info : stream_params.stream_config.stream_map) {
      if (info.audio_channel_allocation &
          bluetooth::le_audio::codec_spec_conf::kLeAudioLocationAnyLeft) {
        left_cis_handle = info.stream_handle;
      }
      if (info.audio_channel_allocation &
          bluetooth::le_audio::codec_spec_conf::kLeAudioLocationAnyRight) {
        right_cis_handle = info.stream_handle;
      }
    }

    if (stream_params.stream_config.codec_frames_blocks_per_sdu != 1) {
      log::error("Codec Frame Blocks of {} is not supported by the software encoding",
                 +stream_params.stream_config.codec_frames_blocks_per_sdu);
    }

    uint16_t byte_count = stream_params.stream_config.octets_per_codec_frame;
    bool mix_to_mono = (left_cis_handle == 0) || (right_cis_handle == 0);
    if (mix_to_mono) {
      std::vector<uint8_t> mono =
              mono_blend(data, bytes_per_sample, number_of_required_samples_per_channel);
      if (left_cis_handle) {
        sw_enc_left->Encode(mono.data(), 1, byte_count);
      }

      if (right_cis_handle) {
        sw_enc_left->Encode(mono.data(), 1, byte_count);
      }
    } else {
      sw_enc_left->Encode(data.data(), 2, byte_count);
      sw_enc_right->Encode(data.data() + bytes_per_sample, 2, byte_count);
    }

    log::debug("left_cis_handle: {} right_cis_handle: {}", left_cis_handle, right_cis_handle);
    /* Send data to the controller */
    if (left_cis_handle) {
      IsoManager::GetInstance()->SendIsoData(
              left_cis_handle, (const uint8_t*)sw_enc_left->GetDecodedSamples().data(),
              sw_enc_left->GetDecodedSamples().size() * 2);
    }

    if (right_cis_handle) {
      IsoManager::GetInstance()->SendIsoData(
              right_cis_handle, (const uint8_t*)sw_enc_right->GetDecodedSamples().data(),
              sw_enc_right->GetDecodedSamples().size() * 2);
    }
  }

  void PrepareAndSendToSingleCis(
          const std::vector<uint8_t>& data,
          const struct bluetooth::le_audio::stream_parameters& stream_params) {
    uint16_t num_channels = stream_params.num_of_channels;
    uint16_t cis_handle = stream_params.stream_config.stream_map.front().stream_handle;

    uint16_t number_of_required_samples_per_channel = sw_enc_left->GetNumOfSamplesPerChannel();
    uint8_t bytes_per_sample = sw_enc_left->GetNumOfBytesPerSample();
    if ((int)data.size() <
        (bytes_per_sample * num_channels * number_of_required_samples_per_channel)) {
      log::error("Missing samples");
      return;
    }

    if (stream_params.stream_config.codec_frames_blocks_per_sdu != 1) {
      log::error("Codec Frame Blocks of {} is not supported by the software encoding",
                 +stream_params.stream_config.codec_frames_blocks_per_sdu);
    }

    uint16_t byte_count = stream_params.stream_config.octets_per_codec_frame;
    bool mix_to_mono = (num_channels == 1);
    if (mix_to_mono) {
      /* Since we always get two channels from framework, lets make it mono here
       */
      std::vector<uint8_t> mono =
              mono_blend(data, bytes_per_sample, number_of_required_samples_per_channel);
      sw_enc_left->Encode(mono.data(), 1, byte_count);
    } else {
      sw_enc_left->Encode((const uint8_t*)data.data(), 2, byte_count);
      // Output to the left channel buffer with `byte_count` offset
      sw_enc_right->Encode((const uint8_t*)data.data() + 2, 2, byte_count,
                           &sw_enc_left->GetDecodedSamples(), byte_count);
    }

    IsoManager::GetInstance()->SendIsoData(cis_handle,
                                           (const uint8_t*)sw_enc_left->GetDecodedSamples().data(),
                                           sw_enc_left->GetDecodedSamples().size() * 2);
  }

  const struct bluetooth::le_audio::stream_configuration* GetStreamSinkConfiguration(
          LeAudioDeviceGroup* group) {
    const struct bluetooth::le_audio::stream_configuration* stream_conf = &group->stream_conf;
    log::info("group_id: {}", group->group_id_);
    if (stream_conf->stream_params.sink.stream_config.stream_map.size() == 0) {
      return nullptr;
    }

    log::info("configuration: {}", stream_conf->conf->name);
    return stream_conf;
  }

  void OnAudioDataReady(const std::vector<uint8_t>& data) {
    if ((active_group_id_ == bluetooth::groups::kGroupUnknown) ||
        (audio_sender_state_ != AudioState::STARTED)) {
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      log::error("There is no streaming group available");
      return;
    }

    auto stream_conf = group->stream_conf;
    if ((stream_conf.stream_params.sink.num_of_devices > 2) ||
        (stream_conf.stream_params.sink.num_of_devices == 0) ||
        stream_conf.stream_params.sink.stream_config.stream_map.empty()) {
      log::error("Stream configufation is not valid.");
      return;
    }

    if ((stream_conf.stream_params.sink.num_of_devices == 2) ||
        (stream_conf.stream_params.sink.stream_config.stream_map.size() == 2)) {
      /* Streaming to two devices or one device with 2 CISes */
      PrepareAndSendToTwoCises(data, stream_conf.stream_params.sink);
    } else {
      /* Streaming to one device and 1 CIS */
      PrepareAndSendToSingleCis(data, stream_conf.stream_params.sink);
    }
  }

  void CleanCachedMicrophoneData() {
    cached_channel_timestamp_ = 0;
    cached_channel_ = nullptr;
  }

  /* Handles audio data packets coming from the controller */
  void HandleIncomingCisData(uint8_t* data, uint16_t size, uint16_t cis_conn_hdl,
                             uint32_t timestamp) {
    /* Get only one channel for MONO microphone */
    /* Gather data for channel */
    if ((active_group_id_ == bluetooth::groups::kGroupUnknown) ||
        (audio_receiver_state_ != AudioState::STARTED)) {
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      log::error("There is no streaming group available");
      return;
    }

    uint16_t left_cis_handle = 0;
    uint16_t right_cis_handle = 0;
    for (auto const& info : group->stream_conf.stream_params.source.stream_config.stream_map) {
      // Use the left channel decoder for the Mono Audio microphone
      auto is_mono = info.audio_channel_allocation ==
                     bluetooth::le_audio::codec_spec_conf::kLeAudioLocationMonoAudio;
      if (is_mono || (info.audio_channel_allocation &
                      bluetooth::le_audio::codec_spec_conf::kLeAudioLocationAnyLeft)) {
        left_cis_handle = info.stream_handle;
      }
      if (info.audio_channel_allocation &
          bluetooth::le_audio::codec_spec_conf::kLeAudioLocationAnyRight) {
        right_cis_handle = info.stream_handle;
      }
    }

    auto decoder = sw_dec_left.get();
    if (cis_conn_hdl == left_cis_handle) {
      decoder = sw_dec_left.get();
    } else if (cis_conn_hdl == right_cis_handle) {
      decoder = sw_dec_right.get();
    } else {
      log::error("Received data for unknown handle: {:04x}", cis_conn_hdl);
      return;
    }

    if (!left_cis_handle || !right_cis_handle) {
      /* mono or just one device connected */
      decoder->Decode(data, size);
      SendAudioDataToAF(&decoder->GetDecodedSamples());
      return;
    }
    /* both devices are connected */

    if (cached_channel_ == nullptr || cached_channel_->GetDecodedSamples().empty()) {
      /* First packet received, cache it. We need both channel data to send it
       * to AF. */
      decoder->Decode(data, size);
      cached_channel_timestamp_ = timestamp;
      cached_channel_ = decoder;
      return;
    }

    /* We received either data for the other audio channel, or another
     * packet for same channel */
    if (cached_channel_ != decoder) {
      /* It's data for the 2nd channel */
      if (timestamp == cached_channel_timestamp_) {
        /* Ready to mix data and send out to AF */
        decoder->Decode(data, size);
        SendAudioDataToAF(&sw_dec_left->GetDecodedSamples(), &sw_dec_right->GetDecodedSamples());

        CleanCachedMicrophoneData();
        return;
      }

      /* 2nd Channel is in the future compared to the cached data.
       Send the cached data to AF, and keep the new channel data in cache.
       This should happen only during stream setup */
      SendAudioDataToAF(&decoder->GetDecodedSamples());

      decoder->Decode(data, size);
      cached_channel_timestamp_ = timestamp;
      cached_channel_ = decoder;
      return;
    }

    /* Data for same channel received. 2nd channel is down/not sending
     * data */

    /* Send the cached data out */
    SendAudioDataToAF(&decoder->GetDecodedSamples());

    /* Cache the data in case 2nd channel connects */
    decoder->Decode(data, size);
    cached_channel_timestamp_ = timestamp;
    cached_channel_ = decoder;
  }

  void SendAudioDataToAF(std::vector<int16_t>* left, std::vector<int16_t>* right = nullptr) {
    uint16_t to_write = 0;
    uint16_t written = 0;

    bool af_is_stereo = (audio_framework_sink_config.num_channels == 2);
    bool bt_got_stereo = (left != nullptr) & (right != nullptr);

    if (!af_is_stereo) {
      if (!bt_got_stereo) {
        std::vector<int16_t>* mono = left ? left : right;
        /* mono audio over bluetooth, audio framework expects mono */
        to_write = sizeof(int16_t) * mono->size();
        written = le_audio_sink_hal_client_->SendData((uint8_t*)mono->data(), to_write);
      } else {
        /* stereo audio over bluetooth, audio framework expects mono */
        for (size_t i = 0; i < left->size(); i++) {
          (*left)[i] = ((*left)[i] + (*right)[i]) / 2;
        }
        to_write = sizeof(int16_t) * left->size();
        written = le_audio_sink_hal_client_->SendData((uint8_t*)left->data(), to_write);
      }
    } else {
      /* mono audio over bluetooth, audio framework expects stereo
       * Here we handle stream without checking bt_got_stereo flag.
       */
      const size_t mono_size = left ? left->size() : right->size();
      std::vector<uint16_t> mixed(mono_size * 2);

      for (size_t i = 0; i < mono_size; i++) {
        mixed[2 * i] = left ? (*left)[i] : (*right)[i];
        mixed[2 * i + 1] = right ? (*right)[i] : (*left)[i];
      }
      to_write = sizeof(int16_t) * mixed.size();
      written = le_audio_sink_hal_client_->SendData((uint8_t*)mixed.data(), to_write);
    }

    /* TODO: What to do if not all data sinked ? */
    if (written != to_write) {
      log::error("not all data sinked");
    }
  }

  void ConfirmLocalAudioSourceStreamingRequest() {
    le_audio_source_hal_client_->ConfirmStreamingRequest();

    LeAudioLogHistory::Get()->AddLogHistory(
            kLogBtCallAf, active_group_id_, RawAddress::kEmpty, kLogAfResumeConfirm + "LocalSource",
            "s_state: " + ToString(audio_sender_state_) + "-> STARTED");

    audio_sender_state_ = AudioState::STARTED;
  }

  void ConfirmLocalAudioSinkStreamingRequest() {
    le_audio_sink_hal_client_->ConfirmStreamingRequest();

    LeAudioLogHistory::Get()->AddLogHistory(
            kLogBtCallAf, active_group_id_, RawAddress::kEmpty, kLogAfResumeConfirm + "LocalSink",
            "r_state: " + ToString(audio_receiver_state_) + "-> STARTED");

    audio_receiver_state_ = AudioState::STARTED;
  }

  void StartSendingAudio(int group_id) {
    log::info("");

    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    LeAudioDevice* device = group->GetFirstActiveDevice();
    log::assert_that(device, "Shouldn't be called without an active device.");

    /* Assume 2 ases max just for now. */
    auto* stream_conf = GetStreamSinkConfiguration(group);
    if (stream_conf == nullptr) {
      log::error("could not get sink configuration");
      groupStateMachine_->StopStream(group);
      return;
    }

    log::debug("Sink stream config (#{}):\n",
               static_cast<int>(stream_conf->stream_params.sink.stream_config.stream_map.size()));
    for (auto info : stream_conf->stream_params.sink.stream_config.stream_map) {
      log::debug("Cis handle: 0x{:02x}, allocation 0x{:04x}\n", info.stream_handle,
                 info.audio_channel_allocation);
    }
    log::debug("Source stream config (#{}):\n",
               static_cast<int>(stream_conf->stream_params.source.stream_config.stream_map.size()));
    for (auto info : stream_conf->stream_params.source.stream_config.stream_map) {
      log::debug("Cis handle: 0x{:02x}, allocation 0x{:04x}\n", info.stream_handle,
                 info.audio_channel_allocation);
    }

    uint16_t remote_delay_ms =
            group->GetRemoteDelay(bluetooth::le_audio::types::kLeAudioDirectionSink);
    if (CodecManager::GetInstance()->GetCodecLocation() ==
        bluetooth::le_audio::types::CodecLocation::HOST) {
      if (sw_enc_left || sw_enc_right) {
        log::warn("The encoder instance should have been already released.");
      }
      sw_enc_left = bluetooth::le_audio::CodecInterface::CreateInstance(stream_conf->codec_id);
      auto codec_status =
              sw_enc_left->InitEncoder(audio_framework_source_config, current_encoder_config_);
      if (codec_status != bluetooth::le_audio::CodecInterface::Status::STATUS_OK) {
        log::error("Left channel codec setup failed with err: {}", codec_status);
        groupStateMachine_->StopStream(group);
        return;
      }

      sw_enc_right = bluetooth::le_audio::CodecInterface::CreateInstance(stream_conf->codec_id);
      codec_status =
              sw_enc_right->InitEncoder(audio_framework_source_config, current_encoder_config_);
      if (codec_status != bluetooth::le_audio::CodecInterface::Status::STATUS_OK) {
        log::error("Right channel codec setup failed with err: {}", codec_status);
        groupStateMachine_->StopStream(group);
        return;
      }
    }

    le_audio_source_hal_client_->UpdateRemoteDelay(remote_delay_ms);

    /* We update the target audio allocation before streamStarted so that the CodecManager would
     * already know how to configure the encoder once we confirm the streaming request. */
    CodecManager::GetInstance()->UpdateActiveAudioConfig(
            group->stream_conf.stream_params,
            std::bind(&LeAudioClientImpl::UpdateAudioConfigToHal, weak_factory_.GetWeakPtr(),
                      std::placeholders::_1, std::placeholders::_2),
            ::bluetooth::le_audio::types::kLeAudioDirectionSink);

    ConfirmLocalAudioSourceStreamingRequest();

    /* After confirming the streaming request, if no Stream Active API is available, we need to
     * send an additional update with the currently active audio channel configuration (in case one
     * of the earbuds is not yet connected) so that the offloader would know if any channel mixing
     * (and sending joint-stereo to one CIS) is required until the other bud joins the stream.
     * NOTE: With the Stream Active API available, both information is passed with the initial call.
     */
    if (!LeAudioHalVerifier::SupportsStreamActiveApi()) {
      CodecManager::GetInstance()->UpdateActiveAudioConfig(
              group->stream_conf.stream_params,
              std::bind(&LeAudioClientImpl::UpdateAudioConfigToHal, weak_factory_.GetWeakPtr(),
                        std::placeholders::_1, std::placeholders::_2),
              bluetooth::le_audio::types::kLeAudioDirectionSink);
    }
  }

  const struct bluetooth::le_audio::stream_configuration* GetStreamSourceConfiguration(
          LeAudioDeviceGroup* group) {
    const struct bluetooth::le_audio::stream_configuration* stream_conf = &group->stream_conf;
    if (stream_conf->stream_params.source.stream_config.stream_map.size() == 0) {
      return nullptr;
    }
    log::info("configuration: {}", stream_conf->conf->name);
    return stream_conf;
  }

  void StartReceivingAudio(int group_id) {
    log::info("");

    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    auto* stream_conf = GetStreamSourceConfiguration(group);
    if (!stream_conf) {
      log::warn(
              "Could not get source configuration for group {} probably microphone not configured",
              active_group_id_);
      groupStateMachine_->StopStream(group);
      return;
    }

    uint16_t remote_delay_ms =
            group->GetRemoteDelay(bluetooth::le_audio::types::kLeAudioDirectionSource);

    CleanCachedMicrophoneData();

    if (CodecManager::GetInstance()->GetCodecLocation() ==
        bluetooth::le_audio::types::CodecLocation::HOST) {
      if (sw_dec_left.get() || sw_dec_right.get()) {
        log::warn("The decoder instance should have been already released.");
      }
      sw_dec_left = bluetooth::le_audio::CodecInterface::CreateInstance(stream_conf->codec_id);
      auto codec_status =
              sw_dec_left->InitDecoder(current_decoder_config_, audio_framework_sink_config);
      if (codec_status != bluetooth::le_audio::CodecInterface::Status::STATUS_OK) {
        log::error("Left channel codec setup failed with err: {}", codec_status);
        groupStateMachine_->StopStream(group);
        return;
      }

      sw_dec_right = bluetooth::le_audio::CodecInterface::CreateInstance(stream_conf->codec_id);
      codec_status =
              sw_dec_right->InitDecoder(current_decoder_config_, audio_framework_sink_config);
      if (codec_status != bluetooth::le_audio::CodecInterface::Status::STATUS_OK) {
        log::error("Right channel codec setup failed with err: {}", codec_status);
        groupStateMachine_->StopStream(group);
        return;
      }
    }

    le_audio_sink_hal_client_->UpdateRemoteDelay(remote_delay_ms);

    /* We update the target audio allocation before streamStarted so that the CodecManager would
     * already know how to configure the encoder once we confirm the streaming request. */
    CodecManager::GetInstance()->UpdateActiveAudioConfig(
            group->stream_conf.stream_params,
            std::bind(&LeAudioClientImpl::UpdateAudioConfigToHal, weak_factory_.GetWeakPtr(),
                      std::placeholders::_1, std::placeholders::_2),
            ::bluetooth::le_audio::types::kLeAudioDirectionSource);

    ConfirmLocalAudioSinkStreamingRequest();

    /* After confirming the streaming request, if no Stream Active API is available, we need to
     * send an additional update with the currently active audio channel configuration (in case one
     * of the earbuds is not yet connected) so that the offloader would know if any channel mixing
     * (and sending joint-stereo to one CIS) is required until the other bud joins the stream.
     * NOTE: With the Stream Active API available, both information is passed with the initial call.
     */
    if (!LeAudioHalVerifier::SupportsStreamActiveApi()) {
      CodecManager::GetInstance()->UpdateActiveAudioConfig(
              group->stream_conf.stream_params,
              std::bind(&LeAudioClientImpl::UpdateAudioConfigToHal, weak_factory_.GetWeakPtr(),
                        std::placeholders::_1, std::placeholders::_2),
              bluetooth::le_audio::types::kLeAudioDirectionSource);
    }
  }

  void SuspendAudio(void) {
    CancelStreamingRequest();

    if (sw_enc_left) {
      sw_enc_left.reset();
    }
    if (sw_enc_right) {
      sw_enc_right.reset();
    }
    if (sw_dec_left) {
      sw_dec_left.reset();
    }
    if (sw_dec_right) {
      sw_dec_right.reset();
    }
    CleanCachedMicrophoneData();
  }

  void StopAudio(void) {
    SuspendAudio();
    stack::l2cap::get_interface().L2CA_SetEcosystemBaseInterval(0 /* clear recommendation */);
  }

  void printCurrentStreamConfiguration(std::stringstream& stream) {
    auto config_printer = [&stream](LeAudioCodecConfiguration& conf) {
      stream << "\tsample rate: " << +conf.sample_rate << ", chan: " << +conf.num_channels
             << ", bits: " << +conf.bits_per_sample
             << ", data_interval_us: " << +conf.data_interval_us << "\n";
    };

    stream << "\n";
    stream << "  Speaker codec config (audio framework):\n";
    stream << "\taudio sender state: " << audio_sender_state_ << "\n";
    config_printer(audio_framework_source_config);

    stream << "  Microphone codec config (audio framework):\n";
    stream << "\taudio receiver state: " << audio_receiver_state_ << "\n";
    config_printer(audio_framework_sink_config);

    stream << "  Speaker codec config (SW encoder):\n";
    config_printer(current_encoder_config_);

    stream << "  Microphone codec config (SW decoder):\n";
    config_printer(current_decoder_config_);
  }

  void Dump(int fd) {
    std::stringstream stream;

    stream << "  APP ID: " << +gatt_if_ << "\n";
    stream << "  TBS state: " << (in_call_ ? " In call" : "No calls") << "\n";
    stream << "  Active group: " << +active_group_id_ << "\n";
    stream << "  Reconnection mode: "
           << (reconnection_mode_ == BTM_BLE_BKG_CONNECT_ALLOW_LIST ? "Allow List"
                                                                    : "Targeted Announcements")
           << "\n";
    stream << "  Configuration: " << bluetooth::common::ToString(configuration_context_type_)
           << " (" << loghex(static_cast<uint16_t>(configuration_context_type_)) << ")\n";
    stream << "  Local source metadata context type mask: "
           << local_metadata_context_types_.source.to_string() << "\n";
    stream << "  Local sink metadata context type mask: "
           << local_metadata_context_types_.sink.to_string() << "\n";
    stream << "  Sink listening mode: " << (sink_monitor_mode_ ? "true" : "false") << "\n";
    if (sink_monitor_notified_status_) {
      stream << "  Local sink notified state: "
             << static_cast<int>(sink_monitor_notified_status_.value()) << "\n";
    }
    stream << "  Source monitor mode: " << (source_monitor_mode_ ? "true" : "false") << "\n";
    if (source_monitor_notified_status_) {
      dprintf(fd, "  Local source notified state: %d\n",
              static_cast<int>(source_monitor_notified_status_.value()));
    }

    auto codec_loc = CodecManager::GetInstance()->GetCodecLocation();
    if (codec_loc == bluetooth::le_audio::types::CodecLocation::HOST) {
      stream << "  Codec location: HOST\n";
    } else if (codec_loc == bluetooth::le_audio::types::CodecLocation::CONTROLLER) {
      stream << "  Codec location: CONTROLLER\n";
    } else if (codec_loc == bluetooth::le_audio::types::CodecLocation::ADSP) {
      stream << "  Codec location: ADSP"
             << (CodecManager::GetInstance()->IsUsingCodecExtensibility() ? " (codec extensibility)"
                                                                          : "")
             << "\n";
    } else {
      dprintf(fd, "  Codec location: UNKNOWN\n");
    }

    stream << "  Stream creation speed: ";
    for (auto t : stream_speed_history_) {
      t.Dump(stream);
      stream << ",";
    }
    stream << "\n";
    printCurrentStreamConfiguration(stream);
    stream << "\n";
    aseGroups_.Dump(stream, active_group_id_);
    stream << "\n ";
    stream << "  Not grouped devices:\n";
    leAudioDevices_.Dump(stream, bluetooth::groups::kGroupUnknown);

    dprintf(fd, "%s", stream.str().c_str());

    if (leAudioHealthStatus_) {
      leAudioHealthStatus_->DebugDump(fd);
    }
  }

  void Cleanup() {
    StopVbcCloseTimeout();
    StopSuspendTimeout();

    if (active_group_id_ != bluetooth::groups::kGroupUnknown) {
      /* Bluetooth turned off while streaming */
      StopAudio();
      SetUnicastMonitorMode(bluetooth::le_audio::types::kLeAudioDirectionSink, false);
      ClientAudioInterfaceRelease();
    } else {
      /* There may be not stopped Sink HAL client due to set Listening mode */
      if (sink_monitor_mode_) {
        SetUnicastMonitorMode(bluetooth::le_audio::types::kLeAudioDirectionSink, false);
      }
    }
    groupStateMachine_->Cleanup();
    aseGroups_.Cleanup();
    lastNotifiedGroupStreamStatusMap_.clear();
    leAudioDevices_.Cleanup(gatt_if_);
    if (gatt_if_) {
      BTA_GATTC_AppDeregister(gatt_if_);
    }

    if (leAudioHealthStatus_) {
      leAudioHealthStatus_->Cleanup();
    }
  }

  AudioReconfigurationResult UpdateConfigAndCheckIfReconfigurationIsNeeded(
          LeAudioDeviceGroup* group, LeAudioContextType context_type) {
    log::debug("Checking whether to reconfigure from {} to {}",
               ToString(configuration_context_type_), ToString(context_type));

    auto audio_set_conf = group->GetConfiguration(context_type);
    if (!audio_set_conf) {
      return AudioReconfigurationResult::RECONFIGURATION_NOT_POSSIBLE;
    }

    if (group->IsGroupConfiguredTo(*audio_set_conf) && !DsaReconfigureNeeded(group, context_type)) {
      // Assign the new configuration context as it represennts the current
      // use case even when it eventually ends up being the exact same
      // codec and qos configuration.
      if (configuration_context_type_ != context_type) {
        configuration_context_type_ = context_type;
        group->SetConfigurationContextType(context_type);
      }
      return AudioReconfigurationResult::RECONFIGURATION_NOT_NEEDED;
    }

    log::info("Session reconfiguration needed group: {} for context type: {}", group->group_id_,
              ToHexString(context_type));

    configuration_context_type_ = context_type;

    // Note: The local sink config is based on remote device's source config
    //       and vice versa.
    current_decoder_config_ = group->GetAudioSessionCodecConfigForDirection(
            context_type, bluetooth::le_audio::types::kLeAudioDirectionSource);
    current_encoder_config_ = group->GetAudioSessionCodecConfigForDirection(
            context_type, bluetooth::le_audio::types::kLeAudioDirectionSink);
    return AudioReconfigurationResult::RECONFIGURATION_NEEDED;
  }

  /* Returns true if stream is started */
  bool OnAudioResume(LeAudioDeviceGroup* group, int local_direction) {
    auto remote_direction = (local_direction == bluetooth::le_audio::types::kLeAudioDirectionSink
                                     ? bluetooth::le_audio::types::kLeAudioDirectionSource
                                     : bluetooth::le_audio::types::kLeAudioDirectionSink);

    auto remote_contexts = DirectionalRealignMetadataAudioContexts(group, remote_direction);
    ApplyRemoteMetadataAudioContextPolicy(group, remote_contexts, remote_direction);

    if (!remote_contexts.sink.any() && !remote_contexts.source.any()) {
      log::warn("Requested context type not available on the remote side");

      if (source_monitor_mode_) {
        notifyAudioLocalSource(UnicastMonitorModeStatus::STREAMING_REQUESTED_NO_CONTEXT_VALIDATE);

        return false;
      }

      if (leAudioHealthStatus_) {
        leAudioHealthStatus_->AddStatisticForGroup(
                group, LeAudioHealthGroupStatType::STREAM_CONTEXT_NOT_AVAILABLE);
      }
      return false;
    }

    return GroupStream(group, configuration_context_type_, remote_contexts);
  }

  void OnAudioSuspend() {
    if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
      log::warn(", there is no longer active group");
      return;
    }

    if (stack_config_get_interface()->get_pts_le_audio_disable_ases_before_stopping()) {
      log::info("Stream disable_timer_ started");
      if (alarm_is_scheduled(disable_timer_)) {
        alarm_cancel(disable_timer_);
      }

      alarm_set_on_mloop(
              disable_timer_, kAudioDisableTimeoutMs,
              [](void* data) {
                if (instance) {
                  auto const group_id = PTR_TO_INT(data);
                  log::debug("No resume request received. Suspend the group ID: {}", group_id);
                  instance->GroupSuspend(group_id);
                }
              },
              INT_TO_PTR(active_group_id_));
    }

    StartSuspendTimeout();
  }

  void OnLocalAudioSourceSuspend() {
    log::info("active group_id: {}, IN: audio_receiver_state_: {}, audio_sender_state_: {}",
              active_group_id_, ToString(audio_receiver_state_), ToString(audio_sender_state_));
    LeAudioLogHistory::Get()->AddLogHistory(kLogAfCallBt, active_group_id_, RawAddress::kEmpty,
                                            kLogAfSuspend + "LocalSource",
                                            "r_state: " + ToString(audio_receiver_state_) +
                                                    ", s_state: " + ToString(audio_sender_state_));

    /* Note: This callback is from audio hal driver.
     * Bluetooth peer is a Sink for Audio Framework.
     * e.g. Peer is a speaker
     */
    switch (audio_sender_state_) {
      case AudioState::READY_TO_START:
      case AudioState::STARTED:
        audio_sender_state_ = AudioState::READY_TO_RELEASE;
        break;
      case AudioState::RELEASING:
        return;
      case AudioState::IDLE:
        if (audio_receiver_state_ == AudioState::READY_TO_RELEASE) {
          OnAudioSuspend();
        }
        return;
      case AudioState::READY_TO_RELEASE:
        break;
    }

    /* Last suspends group - triggers group stop */
    if ((audio_receiver_state_ == AudioState::IDLE) ||
        (audio_receiver_state_ == AudioState::READY_TO_RELEASE)) {
      OnAudioSuspend();
      bluetooth::le_audio::MetricsCollector::Get()->OnStreamEnded(active_group_id_);
    }

    log::info("OUT: audio_receiver_state_: {},  audio_sender_state_: {}",
              ToString(audio_receiver_state_), ToString(audio_sender_state_));

    LeAudioLogHistory::Get()->AddLogHistory(kLogBtCallAf, active_group_id_, RawAddress::kEmpty,
                                            kLogAfSuspendConfirm + "LocalSource",
                                            "r_state: " + ToString(audio_receiver_state_) +
                                                    "s_state: " + ToString(audio_sender_state_));
  }

  void OnLocalAudioSourceResume() {
    log::info("active group_id: {}, IN: audio_receiver_state_: {}, audio_sender_state_: {}",
              active_group_id_, ToString(audio_receiver_state_), ToString(audio_sender_state_));
    LeAudioLogHistory::Get()->AddLogHistory(kLogAfCallBt, active_group_id_, RawAddress::kEmpty,
                                            kLogAfResume + "LocalSource",
                                            "r_state: " + ToString(audio_receiver_state_) +
                                                    ", s_state: " + ToString(audio_sender_state_));

    /* Note: This callback is from audio hal driver.
     * Bluetooth peer is a Sink for Audio Framework.
     * e.g. Peer is a speaker
     */
    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      log::error("Invalid group: {}", static_cast<int>(active_group_id_));
      return;
    }

    /* Check if the device resume is allowed */
    if (!group->HasCodecConfigurationForDirection(
                configuration_context_type_, bluetooth::le_audio::types::kLeAudioDirectionSink)) {
      log::error("invalid resume request for context type: {}",
                 ToHexString(configuration_context_type_));
      CancelLocalAudioSourceStreamingRequest();
      return;
    }

    /* Group should not be resumed if:
     * - configured context type is not allowed
     * - updated metadata contains only not allowed context types
     * - is not in call mode (quick metadata updates between audio modes)
     */
    if (!IsInVoipOrRegularCall() &&
        (!group->GetAllowedContextMask(bluetooth::le_audio::types::kLeAudioDirectionSink)
                  .test_all(local_metadata_context_types_.source) ||
         !group->GetAllowedContextMask(bluetooth::le_audio::types::kLeAudioDirectionSink)
                  .test(configuration_context_type_))) {
      log::warn(
              "Block source resume request context types: {}, allowed context mask: {}, "
              "configured: {}",
              ToString(local_metadata_context_types_.source),
              ToString(group->GetAllowedContextMask(
                      bluetooth::le_audio::types::kLeAudioDirectionSink)),
              ToString(configuration_context_type_));
      CancelLocalAudioSourceStreamingRequest();
      return;
    }

    log::debug(
            "active_group_id: {}\n audio_receiver_state: {}\n audio_sender_state: "
            "{}\n configuration_context_type_: {}\n",
            active_group_id_, audio_receiver_state_, audio_sender_state_,
            ToHexString(configuration_context_type_));

    switch (audio_sender_state_) {
      case AudioState::STARTED:
        /* Looks like previous Confirm did not get to the Audio Framework*/
        ConfirmLocalAudioSourceStreamingRequest();
        break;
      case AudioState::IDLE:
        switch (audio_receiver_state_) {
          case AudioState::IDLE:
            /* Stream is not started. Try to do it.*/
            if (OnAudioResume(group, bluetooth::le_audio::types::kLeAudioDirectionSource)) {
              audio_sender_state_ = AudioState::READY_TO_START;
              if (IsReconfigurationTimeoutRunning(active_group_id_)) {
                StopReconfigurationTimeout(active_group_id_,
                                           bluetooth::le_audio::types::kLeAudioDirectionSource);
              }
            } else {
              CancelLocalAudioSourceStreamingRequest();
            }
            break;
          case AudioState::READY_TO_START:
            audio_sender_state_ = AudioState::READY_TO_START;
            if (!IsDirectionAvailableForCurrentConfiguration(
                        group, bluetooth::le_audio::types::kLeAudioDirectionSink)) {
              log::warn(
                      "sink is not configured. \n audio_receiver_state: {} "
                      "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                      "Reconfiguring to {}",
                      ToString(audio_receiver_state_), ToString(audio_sender_state_),
                      group->IsPendingConfiguration(), ToString(configuration_context_type_));
              group->PrintDebugState();
              SetConfigurationAndStopStreamWhenNeeded(group, configuration_context_type_);
            }
            break;
          case AudioState::STARTED:
            audio_sender_state_ = AudioState::READY_TO_START;
            /* If signalling part is completed trigger start sending audio
             * here, otherwise it'll be called on group streaming state callback
             */
            if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
              if (IsDirectionAvailableForCurrentConfiguration(
                          group, bluetooth::le_audio::types::kLeAudioDirectionSink)) {
                StartSendingAudio(active_group_id_);
              } else {
                log::warn(
                        "sink is not configured. \n audio_receiver_state: {} "
                        "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                        "Reconfiguring to {}",
                        ToString(audio_receiver_state_), ToString(audio_sender_state_),
                        group->IsPendingConfiguration(), ToString(configuration_context_type_));
                group->PrintDebugState();
                SetConfigurationAndStopStreamWhenNeeded(group, configuration_context_type_);
              }
            } else {
              log::error(
                      "called in wrong state. \n audio_receiver_state: {} "
                      "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                      "Reconfiguring to {}",
                      ToString(audio_receiver_state_), ToString(audio_sender_state_),
                      group->IsPendingConfiguration(), ToString(configuration_context_type_));
              group->PrintDebugState();
              CancelStreamingRequest();
            }
            break;
          case AudioState::RELEASING:
            /* Group is reconfiguring, reassing state and wait for
             * the stream to be configured
             */
            audio_sender_state_ = audio_receiver_state_;
            break;
          case AudioState::READY_TO_RELEASE:
            /* If the other direction is streaming we can start sending audio */
            if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
              if (IsDirectionAvailableForCurrentConfiguration(
                          group, bluetooth::le_audio::types::kLeAudioDirectionSink)) {
                StopSuspendTimeout();
                StartSendingAudio(active_group_id_);
              } else {
                log::warn(
                        "sink is not configured. \n audio_receiver_state: {} "
                        "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                        "Reconfiguring to {}",
                        ToString(audio_receiver_state_), ToString(audio_sender_state_),
                        group->IsPendingConfiguration(), ToString(configuration_context_type_));
                group->PrintDebugState();
                SetConfigurationAndStopStreamWhenNeeded(group, configuration_context_type_);
              }
            } else {
              log::error(
                      "called in wrong state. \n audio_receiver_state: {} "
                      "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                      "Reconfiguring to {}",
                      ToString(audio_receiver_state_), ToString(audio_sender_state_),
                      group->IsPendingConfiguration(), ToString(configuration_context_type_));
              group->PrintDebugState();
              CancelStreamingRequest();
            }
            break;
        }
        break;
      case AudioState::READY_TO_START:
        log::error(
                "called in wrong state, ignoring double start request. \n "
                "audio_receiver_state: {} \naudio_sender_state: {} \n "
                "isPendingConfiguration: {} \n Reconfiguring to {}",
                ToString(audio_receiver_state_), ToString(audio_sender_state_),
                group->IsPendingConfiguration(), ToString(configuration_context_type_));
        group->PrintDebugState();
        break;
      case AudioState::READY_TO_RELEASE:
        switch (audio_receiver_state_) {
          case AudioState::STARTED:
          case AudioState::READY_TO_START:
          case AudioState::IDLE:
          case AudioState::READY_TO_RELEASE:
            /* Stream is up just restore it */
            StopSuspendTimeout();
            ConfirmLocalAudioSourceStreamingRequest();
            bluetooth::le_audio::MetricsCollector::Get()->OnStreamStarted(
                    active_group_id_, configuration_context_type_);
            break;
          case AudioState::RELEASING:
            /* Keep waiting. After release is done, Audio Hal will be notified
             */
            break;
        }
        break;
      case AudioState::RELEASING:
        /* Keep waiting. After release is done, Audio Hal will be notified */
        break;
    }
  }

  void OnLocalAudioSinkSuspend() {
    log::info("active group_id: {}, IN: audio_receiver_state_: {}, audio_sender_state_: {}",
              active_group_id_, ToString(audio_receiver_state_), ToString(audio_sender_state_));
    LeAudioLogHistory::Get()->AddLogHistory(kLogAfCallBt, active_group_id_, RawAddress::kEmpty,
                                            kLogAfSuspend + "LocalSink",
                                            "r_state: " + ToString(audio_receiver_state_) +
                                                    ", s_state: " + ToString(audio_sender_state_));

    /* If the local sink direction is used, we want to monitor
     * if back channel is actually needed.
     */
    StartVbcCloseTimeout();

    /* Note: This callback is from audio hal driver.
     * Bluetooth peer is a Source for Audio Framework.
     * e.g. Peer is microphone.
     */
    switch (audio_receiver_state_) {
      case AudioState::READY_TO_START:
      case AudioState::STARTED:
        audio_receiver_state_ = AudioState::READY_TO_RELEASE;
        break;
      case AudioState::RELEASING:
        return;
      case AudioState::IDLE:
        if (audio_sender_state_ == AudioState::READY_TO_RELEASE) {
          OnAudioSuspend();
        }
        return;
      case AudioState::READY_TO_RELEASE:
        break;
    }

    /* Last suspends group - triggers group stop */
    if ((audio_sender_state_ == AudioState::IDLE) ||
        (audio_sender_state_ == AudioState::READY_TO_RELEASE)) {
      OnAudioSuspend();
    }

    log::info("OUT: audio_receiver_state_: {},  audio_sender_state_: {}",
              ToString(audio_receiver_state_), ToString(audio_sender_state_));

    LeAudioLogHistory::Get()->AddLogHistory(kLogBtCallAf, active_group_id_, RawAddress::kEmpty,
                                            kLogAfSuspendConfirm + "LocalSink",
                                            "r_state: " + ToString(audio_receiver_state_) +
                                                    "s_state: " + ToString(audio_sender_state_));
  }

  inline bool IsDirectionAvailableForCurrentConfiguration(const LeAudioDeviceGroup* group,
                                                          uint8_t remote_direction) const {
    auto current_config =
            group->IsUsingPreferredAudioSetConfiguration(configuration_context_type_)
                    ? group->GetCachedPreferredConfiguration(configuration_context_type_)
                    : group->GetCachedConfiguration(configuration_context_type_);
    log::debug("configuration_context_type_ = {}, group_id: {}, remote_direction: {}",
               ToString(configuration_context_type_), group->group_id_,
               remote_direction == bluetooth::le_audio::types::kLeAudioDirectionSink ? "Sink"
                                                                                     : "Source");
    if (current_config) {
      log::debug("name = {}, size {}", current_config->name,
                 current_config->confs.get(remote_direction).size());
      return current_config->confs.get(remote_direction).size() != 0;
    }
    log::debug("no cached configuration");
    return false;
  }

  void notifyAudioLocalSink(UnicastMonitorModeStatus status) {
    if (sink_monitor_notified_status_ != status) {
      log::info("Stream monitoring status changed to: {}", static_cast<int>(status));
      sink_monitor_notified_status_ = status;
      callbacks_->OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSink,
                                             status);
    }
  }

  void notifyAudioLocalSource(UnicastMonitorModeStatus status) {
    if (source_monitor_notified_status_ != status) {
      log::info("Source stream monitoring status changed to: {}", static_cast<int>(status));
      source_monitor_notified_status_ = status;
      callbacks_->OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                             status);
    }
  }

  void OnLocalAudioSinkResume() {
    log::info("active group_id: {} IN: audio_receiver_state_: {}, audio_sender_state_: {}",
              active_group_id_, ToString(audio_receiver_state_), ToString(audio_sender_state_));
    LeAudioLogHistory::Get()->AddLogHistory(kLogAfCallBt, active_group_id_, RawAddress::kEmpty,
                                            kLogAfResume + "LocalSink",
                                            "r_state: " + ToString(audio_receiver_state_) +
                                                    ", s_state: " + ToString(audio_sender_state_));

    if (!com::android::bluetooth::flags::leaudio_use_audio_recording_listener()) {
      if (sink_monitor_mode_ && active_group_id_ == bluetooth::groups::kGroupUnknown) {
        if (sink_monitor_notified_status_ != UnicastMonitorModeStatus::STREAMING_REQUESTED) {
          notifyAudioLocalSink(UnicastMonitorModeStatus::STREAMING_REQUESTED);
        }
        CancelLocalAudioSinkStreamingRequest();
        return;
      }
    }

    /* Stop the VBC close watchdog if needed */
    StopVbcCloseTimeout();

    /* Note: This callback is from audio hal driver.
     * Bluetooth peer is a Source for Audio Framework.
     * e.g. Peer is microphone.
     */
    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      log::error("Invalid group: {}", static_cast<int>(active_group_id_));
      return;
    }

    if (audio_receiver_state_ == AudioState::IDLE) {
      /* We need new configuration_context_type_ to be selected before we go any
       * further.
       */
      ReconfigureOrUpdateRemote(group, bluetooth::le_audio::types::kLeAudioDirectionSource);
      log::info("new_configuration_context = {}", ToString(configuration_context_type_));
    }

    /* Check if the device resume is allowed */
    if (!group->HasCodecConfigurationForDirection(
                configuration_context_type_, bluetooth::le_audio::types::kLeAudioDirectionSource)) {
      log::error("invalid resume request for context type: {}",
                 ToHexString(configuration_context_type_));
      CancelLocalAudioSinkStreamingRequest();
      return;
    }

    /* Group should not be resumed if:
     * - configured context type is not allowed
     * - updated metadata contains only not allowed context types
     * - is not in call mode (quick metadata updates between audio modes)
     */
    if (!IsInVoipOrRegularCall() &&
        (!group->GetAllowedContextMask(bluetooth::le_audio::types::kLeAudioDirectionSource)
                  .test_all(local_metadata_context_types_.sink) ||
         !group->GetAllowedContextMask(bluetooth::le_audio::types::kLeAudioDirectionSource)
                  .test(configuration_context_type_))) {
      log::warn(
              "Block sink resume request context types: {} vs allowed context mask: {}, "
              "configured: {}",
              ToString(local_metadata_context_types_.sink),
              ToString(group->GetAllowedContextMask(
                      bluetooth::le_audio::types::kLeAudioDirectionSource)),
              ToString(configuration_context_type_));
      CancelLocalAudioSourceStreamingRequest();
      return;
    }

    log::debug(
            "active_group_id: {}\n audio_receiver_state: {}\n audio_sender_state: "
            "{}\n configuration_context_type_: {}\n group {}\n",
            active_group_id_, audio_receiver_state_, audio_sender_state_,
            ToHexString(configuration_context_type_), group ? " exist " : " does not exist ");

    switch (audio_receiver_state_) {
      case AudioState::STARTED:
        ConfirmLocalAudioSinkStreamingRequest();
        break;
      case AudioState::IDLE:
        switch (audio_sender_state_) {
          case AudioState::IDLE:
            if (OnAudioResume(group, bluetooth::le_audio::types::kLeAudioDirectionSink)) {
              audio_receiver_state_ = AudioState::READY_TO_START;
              if (IsReconfigurationTimeoutRunning(active_group_id_)) {
                StopReconfigurationTimeout(active_group_id_,
                                           bluetooth::le_audio::types::kLeAudioDirectionSink);
              }
            } else {
              CancelLocalAudioSinkStreamingRequest();
            }
            break;
          case AudioState::READY_TO_START:
            audio_receiver_state_ = AudioState::READY_TO_START;
            if (!IsDirectionAvailableForCurrentConfiguration(
                        group, bluetooth::le_audio::types::kLeAudioDirectionSource)) {
              log::warn(
                      "source is not configured. \n audio_receiver_state: {} "
                      "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                      "Reconfiguring to {}",
                      ToString(audio_receiver_state_), ToString(audio_sender_state_),
                      group->IsPendingConfiguration(), ToString(configuration_context_type_));
              group->PrintDebugState();
              SetConfigurationAndStopStreamWhenNeeded(group, configuration_context_type_);
            }
            break;
          case AudioState::STARTED:
            audio_receiver_state_ = AudioState::READY_TO_START;
            /* If signalling part is completed trigger start receiving audio
             * here, otherwise it'll be called on group streaming state callback
             */
            if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
              if (IsDirectionAvailableForCurrentConfiguration(
                          group, bluetooth::le_audio::types::kLeAudioDirectionSource)) {
                StartReceivingAudio(active_group_id_);
              } else {
                log::warn(
                        "source is not configured. \n audio_receiver_state: {} "
                        "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                        "Reconfiguring to {}",
                        ToString(audio_receiver_state_), ToString(audio_sender_state_),
                        group->IsPendingConfiguration(), ToString(configuration_context_type_));
                group->PrintDebugState();
                SetConfigurationAndStopStreamWhenNeeded(group, configuration_context_type_);
              }
            } else {
              log::error(
                      "called in wrong state. \n audio_receiver_state: {} "
                      "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                      "Reconfiguring to {}",
                      ToString(audio_receiver_state_), ToString(audio_sender_state_),
                      group->IsPendingConfiguration(), ToString(configuration_context_type_));
              group->PrintDebugState();
              CancelStreamingRequest();
            }
            break;
          case AudioState::RELEASING:
            /* Group is reconfiguring, reassing state and wait for
             * the stream to be configured
             */
            audio_receiver_state_ = audio_sender_state_;
            break;
          case AudioState::READY_TO_RELEASE:
            /* If the other direction is streaming we can start receiving audio
             */
            if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
              if (IsDirectionAvailableForCurrentConfiguration(
                          group, bluetooth::le_audio::types::kLeAudioDirectionSource)) {
                StopSuspendTimeout();
                StartReceivingAudio(active_group_id_);
              } else {
                log::warn(
                        "source is not configured. \n audio_receiver_state: {} "
                        "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                        "Reconfiguring to {}",
                        ToString(audio_receiver_state_), ToString(audio_sender_state_),
                        group->IsPendingConfiguration(), ToString(configuration_context_type_));
                group->PrintDebugState();
                SetConfigurationAndStopStreamWhenNeeded(group, configuration_context_type_);
              }
            } else {
              log::error(
                      "called in wrong state. \n audio_receiver_state: {} "
                      "\naudio_sender_state: {} \n isPendingConfiguration: {} \n "
                      "Reconfiguring to {}",
                      ToString(audio_receiver_state_), ToString(audio_sender_state_),
                      group->IsPendingConfiguration(), ToString(configuration_context_type_));
              group->PrintDebugState();
              CancelStreamingRequest();
            }
            break;
        }
        break;
      case AudioState::READY_TO_START:
        log::error(
                "Double resume request, just ignore it.. \n audio_receiver_state: "
                "{} \naudio_sender_state: {} \n isPendingConfiguration: {} \n Reconfiguring to {}",
                ToString(audio_receiver_state_), ToString(audio_sender_state_),
                group->IsPendingConfiguration(), ToString(configuration_context_type_));
        group->PrintDebugState();
        break;
      case AudioState::READY_TO_RELEASE:
        switch (audio_sender_state_) {
          case AudioState::STARTED:
          case AudioState::IDLE:
          case AudioState::READY_TO_START:
          case AudioState::READY_TO_RELEASE:
            /* Stream is up just restore it */
            StopSuspendTimeout();
            ConfirmLocalAudioSinkStreamingRequest();
            break;
          case AudioState::RELEASING:
            /* Wait until releasing is completed */
            break;
        }

        break;
      case AudioState::RELEASING:
        /* Wait until releasing is completed */
        break;
    }
  }

  /* Chooses a single context type to use as a key for selecting a single
   * audio set configuration. Contexts used for the metadata can be different
   * than this, but it's reasonable to select a configuration context from
   * the metadata context types.
   */
  LeAudioContextType ChooseConfigurationContextType(AudioContexts available_remote_contexts) {
    log::debug("Got contexts={} in config_context={}",
               bluetooth::common::ToString(available_remote_contexts),
               bluetooth::common::ToString(configuration_context_type_));

    if (IsInCall()) {
      log::debug("In Call preference used.");
      return LeAudioContextType::CONVERSATIONAL;
    }

    /* Mini policy - always prioritize sink+source configurations so that we are
     * sure that for a mixed content we enable all the needed directions.
     */
    if (available_remote_contexts.any()) {
      LeAudioContextType context_priority_list[] = {
              /* Highest priority first */
              LeAudioContextType::CONVERSATIONAL,
              LeAudioContextType::RINGTONE,
              LeAudioContextType::LIVE,
              LeAudioContextType::VOICEASSISTANTS,
              LeAudioContextType::GAME,
              LeAudioContextType::MEDIA,
              LeAudioContextType::EMERGENCYALARM,
              LeAudioContextType::ALERTS,
              LeAudioContextType::INSTRUCTIONAL,
              LeAudioContextType::NOTIFICATIONS,
              LeAudioContextType::SOUNDEFFECTS,
      };
      for (auto ct : context_priority_list) {
        if (available_remote_contexts.test(ct)) {
          log::debug("Selecting configuration context type: {}", ToString(ct));
          return ct;
        }
      }
    }

    /* Use BAP mandated UNSPECIFIED only if we don't have any other valid
     * configuration
     */
    auto fallback_config = LeAudioContextType::UNSPECIFIED;
    if (configuration_context_type_ != LeAudioContextType::UNINITIALIZED) {
      fallback_config = configuration_context_type_;
    }

    log::debug("Selecting configuration context type: {}", ToString(fallback_config));
    return fallback_config;
  }

  bool SetConfigurationAndStopStreamWhenNeeded(LeAudioDeviceGroup* group,
                                               LeAudioContextType new_context_type) {
    auto previous_context_type = configuration_context_type_;

    auto reconfig_result = UpdateConfigAndCheckIfReconfigurationIsNeeded(group, new_context_type);
    /* Even though the reconfiguration may not be needed, this has
     * to be set here as it might be the initial configuration.
     */

    configuration_context_type_ = new_context_type;

    log::info("group_id {}, previous_context {} context type {} ({}), {}", group->group_id_,
              ToString(previous_context_type), ToString(new_context_type),
              ToHexString(new_context_type), ToString(reconfig_result));
    if (reconfig_result == AudioReconfigurationResult::RECONFIGURATION_NOT_NEEDED) {
      return false;
    }

    if (reconfig_result == AudioReconfigurationResult::RECONFIGURATION_NOT_POSSIBLE) {
      return false;
    }

    if (group->GetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      log::debug("Group is not streaming");
      return false;
    }

    StopSuspendTimeout();
    /* If group suspend is scheduled, cancel as we are stopping it anyway */

    /* Need to reconfigure stream. At this point pre_configuration_context_type shall be set */

    initReconfiguration(group, previous_context_type);
    SendAudioGroupCurrentCodecConfigChanged(group);
    return true;
  }

  bool stopStreamIfCurrentContextTypeIsNotAllowed(uint8_t direction, LeAudioDeviceGroup* group,
                                                  AudioContexts local_contexts) {
    AudioContexts allowed_contexts = group->GetAllowedContextMask(direction);

    /* Stream should be suspended if:
     * - updated metadata is only not allowed
     * - there is no metadata (cleared) but configuration is for not allowed context
     */
    if (group->IsStreaming() && !allowed_contexts.test_any(local_contexts) &&
        !(allowed_contexts.test(configuration_context_type_) && local_contexts.none())) {
      /* SuspendForReconfiguration and ReconfigurationComplete is a workaround method to let Audio
       * Framework know that session is suspended. Strem resume would be handled from
       * suspended session context with stopped group.
       */
      SuspendedForReconfiguration();
      ReconfigurationComplete(direction);
      GroupStop(active_group_id_);

      return true;
    }

    return false;
  }

  void OnLocalAudioSourceMetadataUpdate(
          const std::vector<struct playback_track_metadata_v7>& source_metadata, DsaMode dsa_mode) {
    if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
      log::warn(", cannot start streaming if no active group set");
      return;
    }

    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      log::error("Invalid group: {}", static_cast<int>(active_group_id_));
      return;
    }

    log::info(
            "group_id {} state={}, target_state={}, audio_receiver_state_: {}, "
            "audio_sender_state_: {}, dsa_mode: {}",
            group->group_id_, ToString(group->GetState()), ToString(group->GetTargetState()),
            ToString(audio_receiver_state_), ToString(audio_sender_state_),
            static_cast<int>(dsa_mode));

    if (IsReconfigurationTimeoutRunning(group->group_id_)) {
      log::info("Skip it as group is reconfiguring");
      return;
    }

    /* Stop the VBC close timeout timer, since we will reconfigure anyway if the
     * VBC was suspended.
     */
    StopVbcCloseTimeout();

    group->dsa_.mode = dsa_mode;

    /* Set the remote sink metadata context from the playback tracks metadata */
    UpdateSourceLocalMetadataContextTypes(GetAudioContextsFromSourceMetadata(source_metadata));

    /* Check if stream should be suspended due to reamaining only not allowed contexts in metadata
     * or configured context.
     *
     * If device is inCall mode, AF may quickly change metadata from ringing mode to active.
     * To avoid short stream suspend, let's keep stream alive.
     */
    if (com::android::bluetooth::flags::leaudio_stop_updated_to_not_available_context_stream() &&
        !IsInVoipOrRegularCall() &&
        stopStreamIfCurrentContextTypeIsNotAllowed(
                bluetooth::le_audio::types::kLeAudioDirectionSink, group,
                local_metadata_context_types_.source)) {
      log::info(
              "Updated source metadata contexts are not allowed context types: {} | configured: {} "
              "vs allowed context mask: {}",
              ToString(local_metadata_context_types_.source), ToString(configuration_context_type_),
              ToString(group->GetAllowedContextMask(
                      bluetooth::le_audio::types::kLeAudioDirectionSink)));

      return;
    }

    UpdateSourceLocalMetadataContextTypes(
            ChooseMetadataContextType(local_metadata_context_types_.source));

    ReconfigureOrUpdateRemote(group, bluetooth::le_audio::types::kLeAudioDirectionSink);
  }

  /* Applies some predefined policy on the audio context metadata, including
   * special handling of UNSPECIFIED context, which also involves checking
   * context support and availability.
   */
  void ApplyRemoteMetadataAudioContextPolicy(LeAudioDeviceGroup* group,
                                             BidirectionalPair<AudioContexts>& contexts_pair,
                                             int remote_dir) {
    // We expect at least some context when this direction gets enabled
    if (contexts_pair.get(remote_dir).none()) {
      log::warn("invalid/unknown {} context metadata, using 'UNSPECIFIED' instead",
                (remote_dir == bluetooth::le_audio::types::kLeAudioDirectionSink) ? "sink"
                                                                                  : "source");
      contexts_pair.get(remote_dir) = AudioContexts(LeAudioContextType::UNSPECIFIED);
    }

    std::tuple<int, int, AudioState*> remote_directions[] = {
            {bluetooth::le_audio::types::kLeAudioDirectionSink,
             bluetooth::le_audio::types::kLeAudioDirectionSource, &audio_sender_state_},
            {bluetooth::le_audio::types::kLeAudioDirectionSource,
             bluetooth::le_audio::types::kLeAudioDirectionSink, &audio_receiver_state_},
    };

    /* Align with the context availability */
    for (auto entry : remote_directions) {
      int dir, other_dir;
      AudioState* local_hal_state;
      std::tie(dir, other_dir, local_hal_state) = entry;

      /* When a certain context became unavailable while it was already in
       * an active stream, it means that it is unavailable to other clients
       * but we can keep using it.
       */
      auto group_available_contexts = group->GetAvailableContexts(dir);
      if ((*local_hal_state == AudioState::STARTED) ||
          (*local_hal_state == AudioState::READY_TO_START)) {
        group_available_contexts |= group->GetMetadataContexts().get(dir);
      }

      log::debug("Checking contexts: {}, against the available contexts: {}",
                 ToString(contexts_pair.get(dir)), ToString(group_available_contexts));
      auto unavail_contexts = contexts_pair.get(dir) & ~group_available_contexts;
      if (unavail_contexts.none()) {
        continue;
      }

      // Use only available contexts
      contexts_pair.get(dir) &= group_available_contexts;

      // Check we we should add UNSPECIFIED as well in case not available context is also not
      // supported
      auto unavail_but_supported = (unavail_contexts & group->GetSupportedContexts(dir));
      if (unavail_but_supported.none() &&
          group_available_contexts.test(LeAudioContextType::UNSPECIFIED)) {
        contexts_pair.get(dir).set(LeAudioContextType::UNSPECIFIED);

        log::debug("Replaced the unsupported contexts: {} with UNSPECIFIED -> {}",
                   ToString(unavail_contexts), ToString(contexts_pair.get(dir)));
      } else {
        log::debug("Some contexts are supported but currently unavailable: {}!",
                   ToString(unavail_but_supported));
        /* Some of the streamed contexts are support but not available and they
         * were erased from the metadata.
         * TODO: Either filter out these contexts from the stream or do not
         * stream at all if the unavail_but_supported contexts are the only
         * streamed contexts.
         */
      }
    }

    /* Don't mix UNSPECIFIED with any other context
     * Note: This has to be in a separate loop - do not merge it with the above.
     */
    for (auto entry : remote_directions) {
      int dir, other_dir;
      AudioState* local_hal_state;
      std::tie(dir, other_dir, local_hal_state) = entry;

      if (contexts_pair.get(dir).test(LeAudioContextType::UNSPECIFIED)) {
        /* If this directions is streaming just UNSPECIFIED and if other direction is streaming some
         * meaningfull context type,  try to use the meaningful context type
         */
        if (contexts_pair.get(dir) == AudioContexts(LeAudioContextType::UNSPECIFIED)) {
          auto is_other_direction_streaming = (*local_hal_state == AudioState::STARTED) ||
                                              (*local_hal_state == AudioState::READY_TO_START);
          if (is_other_direction_streaming) {
            auto supported_contexts = group->GetAllSupportedSingleDirectionOnlyContextTypes(dir);
            auto common_part = supported_contexts & contexts_pair.get(other_dir);

            log::info(
                    "Other direction is streaming. Possible aligning other direction "
                    "metadata to match the remote {} direction context: {}. Remote {} supported "
                    "contexts: {} ",
                    other_dir == bluetooth::le_audio::types::kLeAudioDirectionSink ? " Sink"
                                                                                   : " Source",
                    ToString(contexts_pair.get(other_dir)),
                    dir == bluetooth::le_audio::types::kLeAudioDirectionSink ? " Sink" : " Source",
                    ToString(supported_contexts));

            if (common_part.value() != 0) {
              contexts_pair.get(dir) = common_part;
            }
          }
        } else {
          log::debug("Removing UNSPECIFIED from the remote {} context: {}",
                     dir == bluetooth::le_audio::types::kLeAudioDirectionSink ? " Sink" : " Source",
                     ToString(contexts_pair.get(other_dir)));
          contexts_pair.get(dir).unset(LeAudioContextType::UNSPECIFIED);
        }
      }
    }

    contexts_pair.sink = ChooseMetadataContextType(contexts_pair.sink);
    contexts_pair.source = ChooseMetadataContextType(contexts_pair.source);

    log::debug("Aligned remote metadata audio context: sink={}, source={}",
               ToString(contexts_pair.sink), ToString(contexts_pair.source));
  }

  void OnLocalAudioSinkMetadataUpdate(const std::vector<record_track_metadata_v7>& sink_metadata) {
    if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
      log::warn(", cannot start streaming if no active group set");
      return;
    }

    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      log::error("Invalid group: {}", static_cast<int>(active_group_id_));
      return;
    }

    log::info(
            "group_id {} state={}, target_state={}, audio_receiver_state_: {}, "
            "audio_sender_state_: {}",
            group->group_id_, ToString(group->GetState()), ToString(group->GetTargetState()),
            ToString(audio_receiver_state_), ToString(audio_sender_state_));

    if (IsReconfigurationTimeoutRunning(group->group_id_)) {
      log::info("Skip it as group is reconfiguring");
      return;
    }

    /* Set remote source metadata context from the recording tracks metadata */
    local_metadata_context_types_.sink = GetAudioContextsFromSinkMetadata(sink_metadata);

    /* Check if stream should be suspended due to only reamaining not allowed contexts in metadata
     * or configured context.
     *
     * If device is inCall mode, AF may quickly change metadata from ringing mode to active.
     * To avoid short stream suspend, let's keep stream alive.
     */
    if (com::android::bluetooth::flags::leaudio_stop_updated_to_not_available_context_stream() &&
        !IsInVoipOrRegularCall() &&
        stopStreamIfCurrentContextTypeIsNotAllowed(
                bluetooth::le_audio::types::kLeAudioDirectionSource, group,
                local_metadata_context_types_.sink)) {
      log::info(
              "Updated sink metadata contexts are not allowed context types: {} | configured: {} "
              "vs allowed context mask: {}",
              ToString(local_metadata_context_types_.sink), ToString(configuration_context_type_),
              ToString(group->GetAllowedContextMask(
                      bluetooth::le_audio::types::kLeAudioDirectionSource)));
      return;
    }

    UpdateSinkLocalMetadataContextTypes(
            ChooseMetadataContextType(local_metadata_context_types_.sink));

    /* Reconfigure or update only if the stream is already started
     * otherwise wait for the local sink to resume.
     */
    if (audio_receiver_state_ == AudioState::STARTED) {
      ReconfigureOrUpdateRemote(group, bluetooth::le_audio::types::kLeAudioDirectionSource);
    }
  }

  BidirectionalPair<AudioContexts> DirectionalRealignMetadataAudioContexts(
          LeAudioDeviceGroup* group, int remote_direction) {
    uint8_t remote_other_direction;
    std::string remote_direction_str;
    std::string remote_other_direction_str;
    AudioState other_direction_hal;

    if (remote_direction == bluetooth::le_audio::types::kLeAudioDirectionSink) {
      remote_other_direction = bluetooth::le_audio::types::kLeAudioDirectionSource;
      remote_direction_str = "Sink";
      remote_other_direction_str = "Source";
      other_direction_hal = audio_receiver_state_;
    } else {
      remote_other_direction = bluetooth::le_audio::types::kLeAudioDirectionSink;
      remote_direction_str = "Source";
      remote_other_direction_str = "Sink";
      other_direction_hal = audio_sender_state_;
    }

    auto is_streaming_other_direction = (other_direction_hal == AudioState::STARTED) ||
                                        (other_direction_hal == AudioState::READY_TO_START);

    auto local_direction = bluetooth::le_audio::types::kLeAudioDirectionBoth & ~remote_direction;
    auto local_other_direction =
            bluetooth::le_audio::types::kLeAudioDirectionBoth & ~remote_other_direction;

    auto is_releasing_for_reconfiguration =
            (((audio_receiver_state_ == AudioState::RELEASING) ||
              (audio_sender_state_ == AudioState::RELEASING)) &&
             group->IsPendingConfiguration() &&
             IsDirectionAvailableForCurrentConfiguration(group, remote_other_direction)) ||
            IsReconfigurationTimeoutRunning(active_group_id_, local_direction);

    auto is_releasing_for_reconfiguration_other_direction =
            is_releasing_for_reconfiguration &
            IsReconfigurationTimeoutRunning(active_group_id_, local_other_direction);

    // Inject conversational when ringtone is played - this is required for all
    // the VoIP applications which are not using the telecom API.
    constexpr AudioContexts possible_voip_contexts =
            LeAudioContextType::RINGTONE | LeAudioContextType::CONVERSATIONAL;
    if (local_metadata_context_types_.source.test_any(possible_voip_contexts) &&
        ((remote_direction == bluetooth::le_audio::types::kLeAudioDirectionSink) ||
         (remote_direction == bluetooth::le_audio::types::kLeAudioDirectionSource &&
          is_streaming_other_direction))) {
      /* Simulate, we are already in the call. Sending RINGTONE when there is
       * no incoming call to accept or reject on TBS could confuse the remote
       * device and interrupt the stream establish procedure.
       */
      if (!IsInCall() && !IsInVoipCall()) {
        SetInVoipCall(true);
      }
    } else if (IsInVoipCall()) {
      /* When determining whether the VoIP has ended or not make sure
       * we check the just updated direction metadata for CONVERSATIONAL
       */
      auto const local_direction_contexts = local_metadata_context_types_.get(local_direction);
      if (!local_direction_contexts.test_any(possible_voip_contexts)) {
        SetInVoipCall(false);
      }
    }

    BidirectionalPair<AudioContexts> remote_metadata = {
            .sink = local_metadata_context_types_.source,
            .source = local_metadata_context_types_.sink};

    auto all_bidirectional_contexts = group->GetAllSupportedBidirectionalContextTypes();
    log::debug("all_bidirectional_contexts {}", ToString(all_bidirectional_contexts));

    /*
     * Detect the gaming scenario and mirror the context to the other direction.
     * Thanks to this, we will be able to configure even Microphone only devices, for the GAME
     * audio context detected on the local audio source, which never gets resumed for such devices.
     */
    if (remote_metadata.sink.test(LeAudioContextType::GAME)) {
      auto ctxs = group->GetSupportedContexts(bluetooth::le_audio::types::kLeAudioDirectionSource) &
                  AudioContexts(LeAudioContextType::GAME) &
                  bluetooth::le_audio::types::kLeAudioContextAllBidir;
      if (ctxs.any()) {
        log::debug(
                "Gaming scenario detected. Use this audio context for the other direction if "
                "supported");
        remote_metadata.source.clear();
        remote_metadata.source.set_all(ctxs);
      }
    }

    /* Make sure we have CONVERSATIONAL when in a call and it is not mixed
     * with any other bidirectional context
     */
    if (IsInCall() || IsInVoipCall()) {
      log::debug("In Call preference used: {}, voip call: {}", IsInCall(), IsInVoipCall());
      remote_metadata.sink.unset_all(all_bidirectional_contexts);
      remote_metadata.source.unset_all(all_bidirectional_contexts);
      remote_metadata.sink.set(LeAudioContextType::CONVERSATIONAL);
      remote_metadata.source.set(LeAudioContextType::CONVERSATIONAL);
    }

    if (!com::android::bluetooth::flags::leaudio_speed_up_reconfiguration_between_call()) {
      UpdateSinkLocalMetadataContextTypes(remote_metadata.source);
      UpdateSourceLocalMetadataContextTypes(remote_metadata.sink);
    }

    if (IsInVoipCall()) {
      log::debug("Unsetting RINGTONE from remote sink");
      remote_metadata.sink.unset(LeAudioContextType::RINGTONE);
    }

    auto is_ongoing_call_on_other_direction =
            is_streaming_other_direction && (IsInVoipCall() || IsInCall());

    log::debug("local_metadata_context_types_.source= {}",
               ToString(local_metadata_context_types_.source));
    log::debug("local_metadata_context_types_.sink= {}",
               ToString(local_metadata_context_types_.sink));
    log::debug("remote_metadata.source= {}", ToString(remote_metadata.source));
    log::debug("remote_metadata.sink= {}", ToString(remote_metadata.sink));
    log::debug("remote_direction= {}", remote_direction_str);
    log::debug("is_streaming_other_direction= {}", is_streaming_other_direction ? "True" : "False");
    log::debug("is_releasing_for_reconfiguration= {}",
               is_releasing_for_reconfiguration ? "True" : "False");
    log::debug("is_releasing_for_reconfiguration_other_direction= {}",
               is_releasing_for_reconfiguration_other_direction ? "True" : "False");
    log::debug("is_ongoing_call_on_other_direction={}",
               is_ongoing_call_on_other_direction ? "True" : "False");

    /* If the other direction is a bidir scenario we might want to take it into the account, but
     * not always. Look below for details.
     */
    auto is_other_direction_bidir =
            remote_metadata.get(remote_other_direction).test_any(all_bidirectional_contexts);

    /* If the not-resumed direction is local source, we might need to take it's metadata,
     * (this way we detect GAME scenario), but local sink metadata is unreliable.
     */
    bool take_unresumed_local_source_metadata_for_mic_only_devices =
            (group->audio_locations_.sink == std::nullopt) &&
            (local_other_direction == bluetooth::le_audio::types::kLeAudioDirectionSource);
    if (is_other_direction_bidir) {
      if (!(is_streaming_other_direction || is_releasing_for_reconfiguration_other_direction) &&
          !take_unresumed_local_source_metadata_for_mic_only_devices) {
        log::debug(
                "The other direction is not streaming bidirectional or is not a reliable source of "
                "metadata, ignore that context.");
        remote_metadata.get(remote_other_direction).clear();
      }
    }

    auto single_direction_only_context_types =
            group->GetAllSupportedSingleDirectionOnlyContextTypes(remote_direction);
    auto single_other_direction_only_context_types =
            group->GetAllSupportedSingleDirectionOnlyContextTypes(remote_other_direction);
    log::debug(
            "single direction only contexts : {} for direction {}, single direction contexts {} "
            "for {}",
            ToString(single_direction_only_context_types), remote_direction_str,
            ToString(single_other_direction_only_context_types), remote_other_direction_str);

    /* Mixed contexts in the voiceback channel scenarios can confuse the remote
     * on how to configure each channel. We should align the other direction
     * metadata for the remote device.
     */
    if (remote_metadata.get(remote_direction).test_any(all_bidirectional_contexts)) {
      log::debug("Aligning the other direction remote metadata to add this direction context");

      if (is_ongoing_call_on_other_direction) {
        /* Other direction is streaming and is in call */
        remote_metadata.get(remote_direction).unset_all(all_bidirectional_contexts);
        remote_metadata.get(remote_direction).set(LeAudioContextType::CONVERSATIONAL);
      } else {
        if (!(is_streaming_other_direction || is_releasing_for_reconfiguration_other_direction) &&
            !take_unresumed_local_source_metadata_for_mic_only_devices) {
          // Do not take the obsolete metadata
          remote_metadata.get(remote_other_direction).clear();
        } else {
          // The other direction was opened when already in a bidirectional scenario that was not a
          // VoIP or a regular Call. We need to figure out which direction metadata is the leading
          // one.
          // Note: We usually remove any bidirectional or the previous direction specific context
          //       from the previous direction metadata and replace it with the just-resumed
          //       direction (but still bidirectional) context. However when recording is started
          //       in a GAME scenario, we don't want to reconfigure to or mix the context with LIVE.
          auto remote_game_uplink_available =
                  group->GetAvailableContexts(le_audio::types::kLeAudioDirectionSource)
                          .test(LeAudioContextType::GAME);
          auto local_game_uplink_active =
                  (audio_sender_state_ == AudioState::STARTED) &&
                  remote_metadata.sink.test(LeAudioContextType::GAME) &&
                  remote_metadata.source.test_any(LeAudioContextType::LIVE |
                                                  LeAudioContextType::CONVERSATIONAL);
          log::debug(
                  "Remote {} metadata change ({}) while having remote {} context ({}) in a "
                  "bidirectional scenario of {}, local_game_uplink_active: {}, "
                  "remote_game_uplink_available: {}",
                  remote_direction_str, ToString(remote_metadata.get(remote_direction)),
                  remote_other_direction_str, ToString(remote_metadata.get(remote_other_direction)),
                  ToString(configuration_context_type_), local_game_uplink_active,
                  remote_game_uplink_available);
          if (local_game_uplink_active && remote_game_uplink_available) {
            remote_metadata.source.clear();
            remote_metadata.source.set(LeAudioContextType::GAME);
          } else {
            remote_metadata.get(remote_other_direction).unset_all(all_bidirectional_contexts);
            remote_metadata.get(remote_other_direction)
                    .unset_all(single_direction_only_context_types);
          }
        }

        remote_metadata.get(remote_other_direction)
                .set_all(remote_metadata.get(remote_direction) &
                         ~single_other_direction_only_context_types);
      }
    }
    log::debug("remote_metadata.source= {}", ToString(remote_metadata.source));
    log::debug("remote_metadata.sink= {}", ToString(remote_metadata.sink));

    if (is_releasing_for_reconfiguration || is_streaming_other_direction) {
      log::debug("Other direction is streaming or there is reconfiguration. Taking its contexts {}",
                 ToString(remote_metadata.get(remote_other_direction)));
      /* If current direction has no valid context or the other direction is
       * bidirectional scenario, take the other direction context as well
       */
      if ((remote_metadata.get(remote_direction).none() &&
           remote_metadata.get(remote_other_direction).any()) ||
          remote_metadata.get(remote_other_direction).test_any(all_bidirectional_contexts)) {
        log::debug("Aligning this direction remote metadata to add the other direction context");
        /* Turn off bidirectional contexts on this direction to avoid mixing
         * with the other direction bidirectional context
         */
        remote_metadata.get(remote_direction).unset_all(all_bidirectional_contexts);
        remote_metadata.get(remote_direction).set_all(remote_metadata.get(remote_other_direction));
      }
    }

    /* Make sure that after alignment no sink only context leaks into the other
     * direction. */
    remote_metadata.source.unset_all(group->GetAllSupportedSingleDirectionOnlyContextTypes(
            bluetooth::le_audio::types::kLeAudioDirectionSink));

    log::debug("final remote_metadata.source= {}", ToString(remote_metadata.source));
    log::debug("final remote_metadata.sink= {}", ToString(remote_metadata.sink));
    return remote_metadata;
  }

  bool ReconfigureOrUpdateRemoteForPTS(LeAudioDeviceGroup* group, int /*remote_direction*/) {
    log::info("{}", group->group_id_);
    // Use common audio stream contexts exposed by the PTS
    auto override_contexts = AudioContexts(0xFFFF);
    for (auto device = group->GetFirstDevice(); device != nullptr;
         device = group->GetNextDevice(device)) {
      override_contexts &= device->GetAvailableContexts();
    }
    if (override_contexts.value() == 0xFFFF) {
      override_contexts = AudioContexts(LeAudioContextType::UNSPECIFIED);
    }
    log::warn("Overriding local_metadata_context_types_: {} with: {}",
              local_metadata_context_types_.source.to_string(), override_contexts.to_string());

    /* Choose the right configuration context */
    auto new_configuration_context = ChooseConfigurationContextType(override_contexts);

    log::info("new_configuration_context= {}.", ToString(new_configuration_context));
    BidirectionalPair<AudioContexts> remote_contexts = {.sink = override_contexts,
                                                        .source = override_contexts};
    return GroupStream(group, new_configuration_context, remote_contexts);
  }

  /* Return true if stream is started */
  bool ReconfigureOrUpdateRemote(LeAudioDeviceGroup* group, int remote_direction) {
    if (stack_config_get_interface()->get_pts_force_le_audio_multiple_contexts_metadata()) {
      return ReconfigureOrUpdateRemoteForPTS(group, remote_direction);
    }

    /* When the local sink and source update their metadata, we need to come up
     * with a coherent set of contexts for either one or both directions,
     * especially when bidirectional scenarios can be triggered be either sink
     * or source metadata update event.
     */
    auto remote_metadata = DirectionalRealignMetadataAudioContexts(group, remote_direction);
    if (!remote_metadata.sink.any() && !remote_metadata.source.any()) {
      log::warn("No valid metadata to update or reconfigure to.");
      return false;
    }

    /* Choose the right configuration context */
    auto config_context_candids = get_bidirectional(remote_metadata);
    auto new_config_context = ChooseConfigurationContextType(config_context_candids);
    log::debug("config_context_candids= {}, new_config_context= {}",
               ToString(config_context_candids), ToString(new_config_context));

    /* For the following contexts we don't actually need HQ audio:
     * LeAudioContextType::NOTIFICATIONS
     * LeAudioContextType::SOUNDEFFECTS
     * LeAudioContextType::INSTRUCTIONAL
     * LeAudioContextType::ALERTS
     * LeAudioContextType::EMERGENCYALARM
     * LeAudioContextType::UNSPECIFIED
     * So do not reconfigure if the remote sink is already available at any
     * quality and these are the only contributors to the current audio stream.
     */
    auto no_reconfigure_contexts =
            LeAudioContextType::NOTIFICATIONS | LeAudioContextType::SOUNDEFFECTS |
            LeAudioContextType::INSTRUCTIONAL | LeAudioContextType::ALERTS |
            LeAudioContextType::EMERGENCYALARM | LeAudioContextType::UNSPECIFIED;
    if (group->IsStreaming() && !group->IsReleasingOrIdle() && config_context_candids.any() &&
        (config_context_candids & ~no_reconfigure_contexts).none() &&
        (configuration_context_type_ != LeAudioContextType::UNINITIALIZED) &&
        (configuration_context_type_ != LeAudioContextType::UNSPECIFIED) &&
        IsDirectionAvailableForCurrentConfiguration(
                group, bluetooth::le_audio::types::kLeAudioDirectionSink)) {
      log::info(
              "There is no need to reconfigure for the sonification events, "
              "staying with the existing configuration context of {}",
              ToString(configuration_context_type_));
      new_config_context = configuration_context_type_;
    }

    /* Do not configure the Voiceback channel if it is already configured.
     * WARNING: This eliminates additional reconfigurations but can
     * lead to unsatisfying audio quality when that direction was
     * already configured with a lower quality.
     */
    if (remote_direction == bluetooth::le_audio::types::kLeAudioDirectionSource) {
      const auto has_audio_source_configured =
              IsDirectionAvailableForCurrentConfiguration(
                      group, bluetooth::le_audio::types::kLeAudioDirectionSource) &&
              (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
      if (has_audio_source_configured) {
        log::info(
                "Audio source is already available in the current configuration "
                "context in {}. Not switching to {} right now.",
                ToString(configuration_context_type_), ToString(new_config_context));
        new_config_context = configuration_context_type_;
      }
    }

    /* Note that the remote device metadata was so far unfiltered when it comes
     * to group context availability, or multiple contexts support flag, so that
     * we could choose the correct configuration for the use case. Now we can
     * align it to meet the metadata usage.
     */
    ApplyRemoteMetadataAudioContextPolicy(group, remote_metadata, remote_direction);
    return ReconfigureOrUpdateMetadata(group, new_config_context, remote_metadata);
  }

  bool DsaReconfigureNeeded(LeAudioDeviceGroup* group, LeAudioContextType context) {
    // Reconfigure if DSA mode changed for media streaming
    if (context != bluetooth::le_audio::types::LeAudioContextType::MEDIA) {
      return false;
    }

    if (group->dsa_.mode != DsaMode::ISO_SW && group->dsa_.mode != DsaMode::ISO_HW) {
      return false;
    }

    if (group->dsa_.active) {
      return false;
    }

    log::info("DSA mode {} requested but not active", group->dsa_.mode);
    return true;
  }

  /* Return true if stream is started */
  bool ReconfigureOrUpdateMetadata(LeAudioDeviceGroup* group,
                                   LeAudioContextType new_configuration_context,
                                   BidirectionalPair<AudioContexts> remote_contexts) {
    if (new_configuration_context != configuration_context_type_ ||
        DsaReconfigureNeeded(group, new_configuration_context)) {
      log::info("Checking whether to change configuration context from {} to {}",
                ToString(configuration_context_type_), ToString(new_configuration_context));

      LeAudioLogHistory::Get()->AddLogHistory(
              kLogAfCallBt, active_group_id_, RawAddress::kEmpty,
              kLogAfMetadataUpdate + "Reconfigure",
              ToString(configuration_context_type_) + "->" + ToString(new_configuration_context));
      auto is_stopping = SetConfigurationAndStopStreamWhenNeeded(group, new_configuration_context);
      if (is_stopping) {
        return false;
      }
    }

    if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      log::info("The {} configuration did not change. Updating the metadata to sink={}, source={}",
                ToString(configuration_context_type_), ToString(remote_contexts.sink),
                ToString(remote_contexts.source));

      LeAudioLogHistory::Get()->AddLogHistory(
              kLogAfCallBt, active_group_id_, RawAddress::kEmpty,
              kLogAfMetadataUpdate + "Updating...",
              "Sink: " + ToString(remote_contexts.sink) +
                      "Source: " + ToString(remote_contexts.source));

      return GroupStream(group, configuration_context_type_, remote_contexts);
    }
    return false;
  }

  static void OnGattReadRspStatic(tCONN_ID conn_id, tGATT_STATUS status, uint16_t hdl, uint16_t len,
                                  uint8_t* value, void* data) {
    if (!instance) {
      return;
    }

    LeAudioDevice* leAudioDevice = instance->leAudioDevices_.FindByConnId(conn_id);

    if (status == GATT_SUCCESS) {
      instance->LeAudioCharValueHandle(conn_id, hdl, len, value);
    } else if (status == GATT_DATABASE_OUT_OF_SYNC) {
      instance->ClearDeviceInformationAndStartSearch(leAudioDevice);
      return;
    } else {
      log::error("Failed to read attribute, hdl: 0x{:04x}, status: 0x{:02x}", hdl,
                 static_cast<int>(status));
      return;
    }

    /* We use data to keep notify connected flag. */
    if (data && !!PTR_TO_INT(data)) {
      leAudioDevice->notify_connected_after_read_ = false;

      /* Update handles, PACs and ASEs when all is read.*/
      btif_storage_leaudio_update_handles_bin(leAudioDevice->address_);
      btif_storage_leaudio_update_pacs_bin(leAudioDevice->address_);
      btif_storage_leaudio_update_ase_bin(leAudioDevice->address_);

      if (leAudioDevice->audio_locations_.sink) {
        btif_storage_set_leaudio_sink_audio_location(
                leAudioDevice->address_, leAudioDevice->audio_locations_.sink->value.to_ulong());
      }
      if (leAudioDevice->audio_locations_.source) {
        btif_storage_set_leaudio_source_audio_location(
                leAudioDevice->address_, leAudioDevice->audio_locations_.source->value.to_ulong());
      }

      instance->connectionReady(leAudioDevice);
    }
  }

  static void OnGattReadMultiRspStatic(tCONN_ID conn_id, tGATT_STATUS status,
                                       tBTA_GATTC_MULTI& handles, uint16_t total_len,
                                       uint8_t* value, void* data) {
    if (!instance) {
      return;
    }

    if (status == GATT_DATABASE_OUT_OF_SYNC) {
      LeAudioDevice* leAudioDevice = instance->leAudioDevices_.FindByConnId(conn_id);
      instance->ClearDeviceInformationAndStartSearch(leAudioDevice);
      return;
    }
    if (status != GATT_SUCCESS) {
      log::error("Failed to read multiple attributes, conn_id: 0x{:04x}, status: 0x{:02x}", conn_id,
                 +status);
      return;
    }

    size_t position = 0;
    int index = 0;
    while (position != total_len) {
      uint8_t* ptr = value + position;
      uint16_t len;
      STREAM_TO_UINT16(len, ptr);
      uint16_t hdl = handles.handles[index];

      if (position + len >= total_len) {
        log::warn("Multi read was too long, value truncated conn_id: 0x{:04x} handle: 0x{:04x}",
                  conn_id, hdl);
        break;
      }

      OnGattReadRspStatic(conn_id, status, hdl, len, ptr,
                          ((index == (handles.num_attr - 1)) ? data : nullptr));

      position += len + 2; /* skip the length of data */
      index++;
    }

    if (handles.num_attr != index) {
      log::warn("Attempted to read {} handles, but received just {} values", +handles.num_attr,
                index);
    }
  }

  void LeAudioHealthSendRecommendation(const RawAddress& address, int group_id,
                                       LeAudioHealthBasedAction action) {
    log::debug("{}, {}, {}", address, group_id, ToString(action));

    if (address != RawAddress::kEmpty && leAudioDevices_.FindByAddress(address)) {
      callbacks_->OnHealthBasedRecommendationAction(address, action);
    }

    if (group_id != bluetooth::groups::kGroupUnknown && aseGroups_.FindById(group_id)) {
      callbacks_->OnHealthBasedGroupRecommendationAction(group_id, action);
    }
  }

  void IsoCigEventsCb(uint16_t event_type, void* data) {
    switch (event_type) {
      case bluetooth::hci::iso_manager::kIsoEventCigOnCreateCmpl: {
        auto* evt = static_cast<cig_create_cmpl_evt*>(data);
        LeAudioDeviceGroup* group = aseGroups_.FindById(evt->cig_id);
        log::assert_that(group, "Group id: {} is null", evt->cig_id);
        groupStateMachine_->ProcessHciNotifOnCigCreate(group, evt->status, evt->cig_id,
                                                       evt->conn_handles);
      } break;
      case bluetooth::hci::iso_manager::kIsoEventCigOnRemoveCmpl: {
        auto* evt = static_cast<cig_remove_cmpl_evt*>(data);
        LeAudioDeviceGroup* group = aseGroups_.FindById(evt->cig_id);
        log::assert_that(group, "Group id: {} is null", evt->cig_id);
        groupStateMachine_->ProcessHciNotifOnCigRemove(evt->status, group);
        remove_group_if_possible(group);
      } break;
      default:
        log::error("Invalid event {}", event_type);
    }
  }

  void IsoCisEventsCb(uint16_t event_type, void* data) {
    switch (event_type) {
      case bluetooth::hci::iso_manager::kIsoEventCisDataAvailable: {
        auto* event = static_cast<bluetooth::hci::iso_manager::cis_data_evt*>(data);

        if (DsaDataConsume(event)) {
          return;
        }

        if (audio_receiver_state_ != AudioState::STARTED) {
          log::error("receiver state not ready, current state={}", ToString(audio_receiver_state_));
          break;
        }

        HandleIncomingCisData(event->p_msg->data + event->p_msg->offset,
                              event->p_msg->len - event->p_msg->offset, event->cis_conn_hdl,
                              event->ts);
      } break;
      case bluetooth::hci::iso_manager::kIsoEventCisEstablishCmpl: {
        auto* event = static_cast<bluetooth::hci::iso_manager::cis_establish_cmpl_evt*>(data);

        LeAudioDevice* leAudioDevice =
                leAudioDevices_.FindByCisConnHdl(event->cig_id, event->cis_conn_hdl);
        if (!leAudioDevice) {
          log::error("no bonded Le Audio Device with CIS: {}", event->cis_conn_hdl);
          break;
        }
        LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

        if (event->max_pdu_mtos > 0) {
          group->SetTransportLatency(bluetooth::le_audio::types::kLeAudioDirectionSink,
                                     event->trans_lat_mtos);
        }
        if (event->max_pdu_stom > 0) {
          group->SetTransportLatency(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                     event->trans_lat_stom);
        }

        if (leAudioHealthStatus_ && (event->status != HCI_SUCCESS)) {
          leAudioHealthStatus_->AddStatisticForGroup(
                  group, LeAudioHealthGroupStatType::STREAM_CREATE_CIS_FAILED);
        }

        groupStateMachine_->ProcessHciNotifCisEstablished(group, leAudioDevice, event);
      } break;
      case bluetooth::hci::iso_manager::kIsoEventCisDisconnected: {
        auto* event = static_cast<bluetooth::hci::iso_manager::cis_disconnected_evt*>(data);

        LeAudioDevice* leAudioDevice =
                leAudioDevices_.FindByCisConnHdl(event->cig_id, event->cis_conn_hdl);
        if (!leAudioDevice) {
          log::error("no bonded Le Audio Device with CIS: {}", event->cis_conn_hdl);
          break;
        }
        LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

        groupStateMachine_->ProcessHciNotifCisDisconnected(group, leAudioDevice, event);
      } break;
      default:
        log::info(", Not handled ISO event");
        break;
    }
  }

  void IsoSetupIsoDataPathCb(uint8_t status, uint16_t conn_handle, uint8_t cig_id) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByCisConnHdl(cig_id, conn_handle);
    /* In case device has been disconnected before data path was setup */
    if (!leAudioDevice) {
      log::warn("Device for CIG {} and using cis_handle 0x{:04x} is disconnected.", cig_id,
                conn_handle);
      return;
    }
    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

    instance->groupStateMachine_->ProcessHciNotifSetupIsoDataPath(group, leAudioDevice, status,
                                                                  conn_handle);
  }

  void IsoRemoveIsoDataPathCb(uint8_t status, uint16_t conn_handle, uint8_t cig_id) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByCisConnHdl(cig_id, conn_handle);

    /* If CIS has been disconnected just before ACL being disconnected by the
     * remote device, leAudioDevice might be already cleared i.e. has no
     * information about conn_handle, when the data path remove compete arrives.
     */
    if (!leAudioDevice) {
      log::warn("Device for CIG {} and using cis_handle 0x{:04x} is disconnected.", cig_id,
                conn_handle);
      return;
    }

    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

    instance->groupStateMachine_->ProcessHciNotifRemoveIsoDataPath(group, leAudioDevice, status,
                                                                   conn_handle);
  }

  void IsoLinkQualityReadCb(uint8_t conn_handle, uint8_t cig_id, uint32_t txUnackedPackets,
                            uint32_t txFlushedPackets, uint32_t txLastSubeventPackets,
                            uint32_t retransmittedPackets, uint32_t crcErrorPackets,
                            uint32_t rxUnreceivedPackets, uint32_t duplicatePackets) {
    LeAudioDevice* leAudioDevice = leAudioDevices_.FindByCisConnHdl(cig_id, conn_handle);
    if (!leAudioDevice) {
      log::warn("device under connection handle: 0x{:x}, has been disconnecected in meantime",
                conn_handle);
      return;
    }
    LeAudioDeviceGroup* group = aseGroups_.FindById(leAudioDevice->group_id_);

    instance->groupStateMachine_->ProcessHciNotifIsoLinkQualityRead(
            group, leAudioDevice, conn_handle, txUnackedPackets, txFlushedPackets,
            txLastSubeventPackets, retransmittedPackets, crcErrorPackets, rxUnreceivedPackets,
            duplicatePackets);
  }

  void HandlePendingDeviceRemove(LeAudioDeviceGroup* group) {
    for (auto device = group->GetFirstDevice(); device != nullptr;
         device = group->GetNextDevice(device)) {
      if (device->GetConnectionState() == DeviceConnectState::REMOVING) {
        if (device->closing_stream_for_disconnection_) {
          device->closing_stream_for_disconnection_ = false;
          log::info("Disconnecting group id: {}, address: {}", group->group_id_, device->address_);
          bool force_acl_disconnect = device->autoconnect_flag_ && group->IsEnabled();
          DisconnectDevice(device, force_acl_disconnect);
        }
        group_remove_node(group, device->address_, true);
      }
    }
  }

  void HandlePendingDeviceDisconnection(LeAudioDeviceGroup* group) {
    log::debug("");

    auto leAudioDevice = group->GetFirstDevice();
    while (leAudioDevice) {
      if (leAudioDevice->closing_stream_for_disconnection_) {
        leAudioDevice->closing_stream_for_disconnection_ = false;
        log::debug("Disconnecting group id: {}, address: {}", group->group_id_,
                   leAudioDevice->address_);
        bool force_acl_disconnect = leAudioDevice->autoconnect_flag_ && group->IsEnabled();
        DisconnectDevice(leAudioDevice, force_acl_disconnect);
      }
      leAudioDevice = group->GetNextDevice(leAudioDevice);
    }
  }

  void UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config,
                              uint8_t remote_direction) {
    if ((remote_direction & bluetooth::le_audio::types::kLeAudioDirectionSink) &&
        le_audio_source_hal_client_) {
      le_audio_source_hal_client_->UpdateAudioConfigToHal(config);
    }
    if ((remote_direction & bluetooth::le_audio::types::kLeAudioDirectionSource) &&
        le_audio_sink_hal_client_) {
      le_audio_sink_hal_client_->UpdateAudioConfigToHal(config);
    }
  }

  void NotifyUpperLayerGroupTurnedIdleDuringCall(int group_id) {
    if (!osi_property_get_bool(kNotifyUpperLayerAboutGroupBeingInIdleDuringCall, false)) {
      return;
    }

    /* If group is inactive, phone is in call and Group is not having CIS
     * connected, notify upper layer about it, so it can decide to create SCO if
     * it is in the handover case
     */
    if ((IsInCall() || IsInVoipCall()) && active_group_id_ == bluetooth::groups::kGroupUnknown) {
      callbacks_->OnGroupStatus(group_id, GroupStatus::TURNED_IDLE_DURING_CALL);
    }
  }

  void speed_start_setup(int group_id, LeAudioContextType context_type, int num_of_connected,
                         bool is_reconfig = false) {
    log::verbose("is_started {} is_reconfig {} num_of_connected {}",
                 speed_tracker_.IsStarted(group_id), is_reconfig, num_of_connected);
    if (!speed_tracker_.IsStarted(group_id)) {
      speed_tracker_.Init(group_id, context_type, num_of_connected);
    }
    if (is_reconfig) {
      speed_tracker_.ReconfigStarted();
    } else {
      speed_tracker_.StartStream();
    }
  }

  void speed_stop_reconfig(int group_id) {
    log::verbose("");
    if (!speed_tracker_.IsStarted(group_id)) {
      return;
    }

    speed_tracker_.ReconfigurationComplete();
  }

  void speed_stream_created(int group_id) {
    log::verbose("");
    if (!speed_tracker_.IsStarted(group_id)) {
      return;
    }

    speed_tracker_.StreamCreated();
  }

  void speed_stop_setup(int group_id) {
    log::verbose("");
    if (!speed_tracker_.IsStarted(group_id)) {
      return;
    }

    if (stream_speed_history_.size() == 10) {
      stream_speed_history_.pop_back();
    }

    speed_tracker_.StopStreamSetup();
    stream_speed_history_.emplace_front(speed_tracker_);
    speed_tracker_.Reset(group_id);
  }

  void notifyGroupStreamStatus(int group_id, GroupStreamStatus groupStreamStatus) {
    GroupStreamStatus newGroupStreamStatus = GroupStreamStatus::IDLE;
    if (groupStreamStatus == GroupStreamStatus::STREAMING) {
      newGroupStreamStatus = GroupStreamStatus::STREAMING;
    }

    auto it = lastNotifiedGroupStreamStatusMap_.find(group_id);

    if (it != lastNotifiedGroupStreamStatusMap_.end()) {
      if (it->second != newGroupStreamStatus) {
        callbacks_->OnGroupStreamStatus(group_id, newGroupStreamStatus);
        it->second = newGroupStreamStatus;
      }
    } else {
      callbacks_->OnGroupStreamStatus(group_id, newGroupStreamStatus);
      lastNotifiedGroupStreamStatusMap_.emplace(group_id, newGroupStreamStatus);
    }
  }

  void handleAsymmetricPhyForUnicast(LeAudioDeviceGroup* group) {
    if (!group->asymmetric_phy_for_unidirectional_cis_supported) {
      return;
    }

    auto it = lastNotifiedGroupStreamStatusMap_.find(group->group_id_);

    if (it != lastNotifiedGroupStreamStatusMap_.end() &&
        it->second == GroupStreamStatus::STREAMING &&
        group->GetSduInterval(bluetooth::le_audio::types::kLeAudioDirectionSource) == 0) {
      SetAsymmetricBlePhy(group, true);
      return;
    }

    SetAsymmetricBlePhy(group, false);
  }

  void reconfigurationComplete(void) {
    // Check which directions were suspended
    uint8_t previously_active_directions = 0;
    if (audio_sender_state_ >= AudioState::READY_TO_START) {
      previously_active_directions |= bluetooth::le_audio::types::kLeAudioDirectionSink;
    }
    if (audio_receiver_state_ >= AudioState::READY_TO_START) {
      previously_active_directions |= bluetooth::le_audio::types::kLeAudioDirectionSource;
    }

    /* We are done with reconfiguration.
     * Clean state and if Audio HAL is waiting, cancel the request
     * so Audio HAL can Resume again.
     */
    CancelStreamingRequest();
    ReconfigurationComplete(previously_active_directions);
    speed_stop_reconfig(active_group_id_);
  }

  void OnStateMachineStatusReportCb(int group_id, GroupStreamStatus status) {
    /* When switching stream between two group, it is important to keep track if given status is for
     * active group or not in order to proper Audio HAL notifications.
     * It means, we should update Audio HAL and clear common resources when group is an active group
     * or active group is already cleared.
     */
    bool is_active_group_operation =
            (group_id == active_group_id_ || active_group_id_ == bluetooth::groups::kGroupUnknown);

    log::info(
            "status: {},  group_id: {}, audio_sender_state {}, audio_receiver_state {}, "
            "is_active_group_operation {}",
            bluetooth::common::ToString(status), group_id,
            bluetooth::common::ToString(audio_sender_state_),
            bluetooth::common::ToString(audio_receiver_state_), is_active_group_operation);
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);

    notifyGroupStreamStatus(group_id, status);

    switch (status) {
      case GroupStreamStatus::STREAMING: {
        if (!is_active_group_operation) {
          log::error("Streaming group {} is no longer active. Stop the group.", group_id);
          GroupStop(group_id);
          return;
        }

        speed_stream_created(group_id);
        bluetooth::le_audio::MetricsCollector::Get()->OnStreamStarted(active_group_id_,
                                                                      configuration_context_type_);

        if (leAudioHealthStatus_) {
          leAudioHealthStatus_->AddStatisticForGroup(
                  group, LeAudioHealthGroupStatType::STREAM_CREATE_SUCCESS);
        }

        if (!group) {
          log::error("Group {} does not exist anymore. This shall not happen", group_id);
          return;
        }

        handleAsymmetricPhyForUnicast(group);

        if ((audio_sender_state_ == AudioState::IDLE) &&
            (audio_receiver_state_ == AudioState::IDLE)) {
          /* Audio Framework is not interested in the stream anymore.
           * Just stop streaming
           */
          log::warn("Stopping stream for group {} as AF not interested.", group_id);
          speed_stop_setup(group_id);
          groupStateMachine_->StopStream(group);
          return;
        }

        /* It might happen that the configuration has already changed, while
         * the group was in the ongoing reconfiguration. We should stop the
         * stream and reconfigure once again.
         */
        if (group->GetConfigurationContextType() != configuration_context_type_) {
          log::debug(
                  "The configuration {} is no longer valid. Stopping the stream to "
                  "reconfigure to {}",
                  ToString(group->GetConfigurationContextType()),
                  ToString(configuration_context_type_));
          speed_stop_setup(group_id);
          initReconfiguration(group, group->GetConfigurationContextType());
          return;
        }

        if (audio_sender_state_ == AudioState::READY_TO_START) {
          StartSendingAudio(group_id);
        } else if (audio_sender_state_ == AudioState::STARTED) {
          /* If we are already sending, the initial configuration was already sent and
           * we might need to just update the current channel mixing information.
           */
          CodecManager::GetInstance()->UpdateActiveAudioConfig(
                  group->stream_conf.stream_params,
                  std::bind(&LeAudioClientImpl::UpdateAudioConfigToHal, weak_factory_.GetWeakPtr(),
                            std::placeholders::_1, std::placeholders::_2),
                  ::bluetooth::le_audio::types::kLeAudioDirectionSink);
        }

        if (audio_receiver_state_ == AudioState::READY_TO_START) {
          StartReceivingAudio(group_id);
        } else if (audio_receiver_state_ == AudioState::STARTED) {
          /* If we are already receiving, the initial configuration was already sent and
           * we might need to just update the current channel mixing information.
           */
          CodecManager::GetInstance()->UpdateActiveAudioConfig(
                  group->stream_conf.stream_params,
                  std::bind(&LeAudioClientImpl::UpdateAudioConfigToHal, weak_factory_.GetWeakPtr(),
                            std::placeholders::_1, std::placeholders::_2),
                  bluetooth::le_audio::types::kLeAudioDirectionSource);
        }

        speed_stop_setup(group_id);
        break;
      }
      case GroupStreamStatus::SUSPENDED:
        speed_tracker_.Reset(group_id);

        if (is_active_group_operation) {
          /** Stop Audio but don't release all the Audio resources */
          SuspendAudio();
        }
        break;
      case GroupStreamStatus::CONFIGURED_BY_USER:
        if (is_active_group_operation) {
          reconfigurationComplete();
        }
        break;
      case GroupStreamStatus::CONFIGURED_AUTONOMOUS:
        /* This state is notified only when
         * groups stays into CONFIGURED state after
         * STREAMING. Peer device uses cache. For the moment
         * it is handled same as IDLE
         */
      case GroupStreamStatus::IDLE: {
        if (is_active_group_operation) {
          if (sw_enc_left) {
            sw_enc_left.reset();
          }
          if (sw_enc_right) {
            sw_enc_right.reset();
          }
          if (sw_dec_left) {
            sw_dec_left.reset();
          }
          if (sw_dec_right) {
            sw_dec_right.reset();
          }
          CleanCachedMicrophoneData();
        }

        if (group) {
          handleAsymmetricPhyForUnicast(group);
          UpdateLocationsAndContextsAvailability(group);
          if (!group->IsPendingConfiguration()) {
            if (is_active_group_operation) {
              if (sink_monitor_mode_) {
                notifyAudioLocalSink(UnicastMonitorModeStatus::STREAMING_SUSPENDED);
              }

              if (source_monitor_mode_) {
                notifyAudioLocalSource(UnicastMonitorModeStatus::STREAMING_SUSPENDED);
              }
            }
          } else {
            if (!is_active_group_operation) {
              log::info("Clear pending configuration flag for group {}", group->group_id_);
              group->ClearPendingConfiguration();
            } else {
              log::debug(
                      "Pending configuration for group_id: {} pre_configuration_context_type_ : {} "
                      "-> "
                      "configuration_context_type_ {}",
                      group->group_id_, ToString(pre_configuration_context_type_),
                      ToString(configuration_context_type_));
              auto remote_direction =
                      kLeAudioContextAllRemoteSource.test(configuration_context_type_)
                              ? bluetooth::le_audio::types::kLeAudioDirectionSource
                              : bluetooth::le_audio::types::kLeAudioDirectionSink;

              /* Reconfiguration to non requiring source scenario */
              if (sink_monitor_mode_ &&
                  (remote_direction == bluetooth::le_audio::types::kLeAudioDirectionSink)) {
                notifyAudioLocalSink(UnicastMonitorModeStatus::STREAMING_SUSPENDED);
              }

              auto remote_contexts =
                      DirectionalRealignMetadataAudioContexts(group, remote_direction);
              ApplyRemoteMetadataAudioContextPolicy(group, remote_contexts, remote_direction);
              log::verbose(
                      "Pending configuration 2 pre_configuration_context_type_ : {} -> "
                      "configuration_context_type_ {}",
                      ToString(pre_configuration_context_type_),
                      ToString(configuration_context_type_));
              if ((configuration_context_type_ != pre_configuration_context_type_) &&
                  GroupStream(group, configuration_context_type_, remote_contexts)) {
                /* If configuration succeed wait for new status. */
                return;
              }
              log::info("Clear pending configuration flag for group {}", group->group_id_);
              group->ClearPendingConfiguration();
              reconfigurationComplete();
            }
          }
        }

        speed_tracker_.Reset(group_id);
        if (is_active_group_operation) {
          CancelStreamingRequest();
        }

        if (group) {
          NotifyUpperLayerGroupTurnedIdleDuringCall(group->group_id_);
          HandlePendingDeviceRemove(group);
          HandlePendingDeviceDisconnection(group);
        }
        break;
      }
      case GroupStreamStatus::RELEASING_AUTONOMOUS:
        /* Remote device releases all the ASEs autonomusly. This should not happen and not sure what
         * is the remote device intention. If remote wants stop the stream then MCS shall be used to
         * stop the stream in a proper way. For a phone call, GTBS shall be used. For now we assume
         * this device has does not want to be used for streaming and mark it as Inactive.
         */
        log::warn("Group {} is doing autonomous release, make it inactive", group_id);
        if (group) {
          group->PrintDebugState();
          groupSetAndNotifyInactive();
        }
        audio_sender_state_ = AudioState::IDLE;
        audio_receiver_state_ = AudioState::IDLE;
        break;
      case GroupStreamStatus::RELEASING:
      case GroupStreamStatus::SUSPENDING:
        if (active_group_id_ != bluetooth::groups::kGroupUnknown &&
            (active_group_id_ == group->group_id_) && !group->IsPendingConfiguration() &&
            (audio_sender_state_ == AudioState::STARTED ||
             audio_receiver_state_ == AudioState::STARTED) &&
            group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) {
          /* If releasing state is happening but it was not initiated either by
           * reconfiguration or Audio Framework actions either by the Active group change,
           * it means that it is some internal state machine error. This is very unlikely and
           * for now just Inactivate the group.
           */
          log::error("Internal state machine error for group {}", group_id);
          group->PrintDebugState();
          groupSetAndNotifyInactive();
          audio_sender_state_ = AudioState::IDLE;
          audio_receiver_state_ = AudioState::IDLE;
          return;
        }

        /* Releasing state shall be always set here, because we do support only single group
         * streaming at the time.  */
        if (audio_sender_state_ != AudioState::IDLE) {
          audio_sender_state_ = AudioState::RELEASING;
        }

        if (audio_receiver_state_ != AudioState::IDLE) {
          audio_receiver_state_ = AudioState::RELEASING;
        }

        if (is_active_group_operation) {
          if (group && group->IsPendingConfiguration()) {
            log::info("Releasing for reconfiguration, don't send anything on CISes");
            SuspendedForReconfiguration();
          }
        }

        break;
      default:
        break;
    }
  }

  void OnUpdatedCisConfiguration(int group_id, uint8_t direction) {
    LeAudioDeviceGroup* group = aseGroups_.FindById(group_id);
    if (!group) {
      log::error("Invalid group_id: {}", group_id);
      return;
    }
    group->UpdateCisConfiguration(direction);
  }

private:
  tGATT_IF gatt_if_;
  bluetooth::le_audio::LeAudioClientCallbacks* callbacks_;
  LeAudioDevices leAudioDevices_;
  LeAudioDeviceGroups aseGroups_;
  LeAudioGroupStateMachine* groupStateMachine_;
  int active_group_id_;
  LeAudioContextType pre_configuration_context_type_;
  LeAudioContextType configuration_context_type_;
  static constexpr char kAllowMultipleContextsInMetadata[] =
          "persist.bluetooth.leaudio.allow.multiple.contexts";
  BidirectionalPair<AudioContexts> in_call_metadata_context_types_;
  BidirectionalPair<AudioContexts> local_metadata_context_types_;
  StreamSpeedTracker speed_tracker_;
  std::deque<StreamSpeedTracker> stream_speed_history_;

  /* Microphone (s) */
  AudioState audio_receiver_state_;
  /* Speaker(s) */
  AudioState audio_sender_state_;
  /* Keep in call state. */
  bool in_call_;
  bool in_voip_call_;
  /* Listen for streaming status on Sink stream */
  bool sink_monitor_mode_;
  /* Sink stream status which has been notified to Service */
  std::optional<UnicastMonitorModeStatus> sink_monitor_notified_status_;
  /* Listen for streaming status on Source stream */
  bool source_monitor_mode_;
  /* Source stream status which has been notified to Service */
  std::optional<UnicastMonitorModeStatus> source_monitor_notified_status_;

  /* Reconnection mode */
  tBTM_BLE_CONN_TYPE reconnection_mode_;
  static constexpr uint64_t kGroupConnectedWatchDelayMs = 3000;
  static constexpr uint64_t kRecoveryReconnectDelayMs = 2000;
  static constexpr uint64_t kAutoConnectAfterOwnDisconnectDelayMs = 1000;
  static constexpr uint64_t kCsisGroupMemberDelayMs = 5000;

  /* LeAudioHealthStatus */
  LeAudioHealthStatus* leAudioHealthStatus_ = nullptr;

  static constexpr char kNotifyUpperLayerAboutGroupBeingInIdleDuringCall[] =
          "persist.bluetooth.leaudio.notify.idle.during.call";

  static constexpr uint16_t kBapMinimumAttMtu = 64;

  /* Current stream configuration - used to set up the software codecs */
  LeAudioCodecConfiguration current_encoder_config_;
  LeAudioCodecConfiguration current_decoder_config_;

  /* Static Audio Framework session configuration.
   *  Resampling will be done inside the bt stack
   */
  LeAudioCodecConfiguration audio_framework_source_config = {
          .num_channels = 2,
          .sample_rate = bluetooth::audio::le_audio::kSampleRate48000,
          .bits_per_sample = bluetooth::audio::le_audio::kBitsPerSample16,
          .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us,
  };

  LeAudioCodecConfiguration audio_framework_sink_config = {
          .num_channels = 2,
          .sample_rate = bluetooth::audio::le_audio::kSampleRate16000,
          .bits_per_sample = bluetooth::audio::le_audio::kBitsPerSample16,
          .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us,
  };

  std::unique_ptr<bluetooth::le_audio::CodecInterface> sw_enc_left;
  std::unique_ptr<bluetooth::le_audio::CodecInterface> sw_enc_right;

  std::unique_ptr<bluetooth::le_audio::CodecInterface> sw_dec_left;
  std::unique_ptr<bluetooth::le_audio::CodecInterface> sw_dec_right;

  std::vector<uint8_t> encoded_data;
  std::unique_ptr<LeAudioSourceAudioHalClient> le_audio_source_hal_client_;
  std::unique_ptr<LeAudioSinkAudioHalClient> le_audio_sink_hal_client_;
  static constexpr uint64_t kAudioSuspentKeepIsoAliveTimeoutMs = 500;
  static constexpr uint64_t kAudioDisableTimeoutMs = 3000;
  static constexpr char kAudioSuspentKeepIsoAliveTimeoutMsProp[] =
          "persist.bluetooth.leaudio.audio.suspend.timeoutms";
  static constexpr uint64_t kAudioReconfigurationTimeoutMs = 1500;
  alarm_t* close_vbc_timeout_;
  alarm_t* suspend_timeout_;

  /* Reconfiguration guard to make sure reconfigration is not broken by unexpected Metadata change.
   * When Reconfiguration is scheduled then
   * 1. BT stack remembers local directions which should be resumed after reconfiguration
   * 2. Blocks another reconfiguration until:
   *      a) all the reconfigured directions has been resumed
   *      b) reconfiguration timeout fires
   */
  alarm_t* reconfiguration_timeout_;
  int reconfiguration_group_ = bluetooth::groups::kGroupUnknown;
  uint8_t reconfiguration_local_directions_ = 0;

  alarm_t* disable_timer_;
  static constexpr uint64_t kDeviceAttachDelayMs = 500;

  uint32_t cached_channel_timestamp_ = 0;
  bluetooth::le_audio::CodecInterface* cached_channel_ = nullptr;

  base::WeakPtrFactory<LeAudioClientImpl> weak_factory_{this};

  std::map<int, GroupStreamStatus> lastNotifiedGroupStreamStatusMap_;

  void ClientAudioInterfaceRelease() {
    auto group = aseGroups_.FindById(active_group_id_);
    if (!group) {
      log::error("Invalid group: {}", static_cast<int>(active_group_id_));
    } else {
      handleAsymmetricPhyForUnicast(group);
      log::info("ClientAudioInterfaceRelease - cleanup");
    }

    auto result = CodecManager::GetInstance()->UpdateActiveUnicastAudioHalClient(
            le_audio_source_hal_client_.get(), le_audio_sink_hal_client_.get(), false);
    log::assert_that(result, "Could not update session to codec manager");

    if (le_audio_source_hal_client_) {
      le_audio_source_hal_client_->Stop();
      le_audio_source_hal_client_.reset();
    }

    if (le_audio_sink_hal_client_) {
      /* Keep session set up to monitor streaming request. This is required if
       * there is another LE Audio device streaming (e.g. Broadcast) and via
       * the session callbacks special action from this Module would be
       * required e.g. to Unicast handover.
       */
      if (com::android::bluetooth::flags::leaudio_use_audio_recording_listener() ||
          !sink_monitor_mode_) {
        local_metadata_context_types_.sink.clear();
        le_audio_sink_hal_client_->Stop();
        le_audio_sink_hal_client_.reset();
      }
    }

    local_metadata_context_types_.source.clear();
    configuration_context_type_ = LeAudioContextType::UNINITIALIZED;

    bluetooth::le_audio::MetricsCollector::Get()->OnStreamEnded(active_group_id_);
  }

  bool DsaDataConsume(bluetooth::hci::iso_manager::cis_data_evt* event) {
    if (active_group_id_ == bluetooth::groups::kGroupUnknown) {
      return false;
    }
    LeAudioDeviceGroup* group = aseGroups_.FindById(active_group_id_);
    if (!group || !group->dsa_.active) {
      return false;
    }

    if (group->dsa_.mode != DsaMode::ISO_SW) {
      log::warn("ISO packets received over HCI in DSA mode: {}", group->dsa_.mode);
      return false;
    }

    if (iso_data_callback == nullptr) {
      log::warn("Dsa data consumer not registered");
      return false;
    }

    uint16_t cis_conn_hdl = event->cis_conn_hdl;
    uint8_t* data = event->p_msg->data + event->p_msg->offset;
    uint16_t size = event->p_msg->len - event->p_msg->offset;
    uint32_t timestamp = event->ts;

    // Find LE Audio device
    LeAudioDevice* leAudioDevice = group->GetFirstDevice();
    while (leAudioDevice != nullptr) {
      if (leAudioDevice->GetDsaCisHandle() == cis_conn_hdl &&
          leAudioDevice->GetDsaDataPathState() == DataPathState::CONFIGURED) {
        break;
      }
      leAudioDevice = group->GetNextDevice(leAudioDevice);
    }
    if (leAudioDevice == nullptr) {
      log::warn("No LE Audio device found for CIS handle: {}", cis_conn_hdl);
      return false;
    }

    bool consumed = iso_data_callback(leAudioDevice->address_, cis_conn_hdl, data, size, timestamp);
    if (consumed) {
      return true;
    } else {
      log::verbose("ISO data consumer not ready to accept data");
      return false;
    }
  }

  void SetAsymmetricBlePhy(LeAudioDeviceGroup* group, bool asymmetric) {
    LeAudioDevice* leAudioDevice = group->GetFirstDevice();
    if (leAudioDevice == nullptr) {
      log::error("Shouldn't be called without a device.");
      return;
    }

    for (auto tmpDevice = leAudioDevice; tmpDevice != nullptr;
         tmpDevice = group->GetNextDevice(tmpDevice)) {
      log::info("tmpDevice->acl_asymmetric_: {}, asymmetric: {}, address: {}, acl_connected: {}",
                tmpDevice->acl_asymmetric_ == asymmetric, asymmetric, tmpDevice->address_,
                get_btm_client_interface().peer.BTM_IsAclConnectionUp(tmpDevice->address_,
                                                                      BT_TRANSPORT_LE));
      if (tmpDevice->acl_asymmetric_ == asymmetric ||
          !get_btm_client_interface().peer.BTM_IsAclConnectionUp(tmpDevice->address_,
                                                                 BT_TRANSPORT_LE)) {
        continue;
      }

      log::info("SetAsymmetricBlePhy: {} for {}", asymmetric, tmpDevice->address_);
      get_btm_client_interface().ble.BTM_BleSetPhy(tmpDevice->address_, PHY_LE_2M,
                                                   asymmetric ? PHY_LE_1M : PHY_LE_2M, 0);
      tmpDevice->acl_asymmetric_ = asymmetric;
    }
  }
};

static void le_audio_health_status_callback(const RawAddress& addr, int group_id,
                                            LeAudioHealthBasedAction action) {
  if (instance) {
    instance->LeAudioHealthSendRecommendation(addr, group_id, action);
  }
}

/* This is a generic callback method for gatt client which handles every client
 * application events.
 */
void le_audio_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
  if (!p_data || !instance) {
    return;
  }

  log::info("event = {}", static_cast<int>(event));

  switch (event) {
    case BTA_GATTC_DEREG_EVT:
      break;

    case BTA_GATTC_NOTIF_EVT:
      instance->LeAudioCharValueHandle(p_data->notify.conn_id, p_data->notify.handle,
                                       p_data->notify.len,
                                       static_cast<uint8_t*>(p_data->notify.value), true);

      if (!p_data->notify.is_notify) {
        BTA_GATTC_SendIndConfirm(p_data->notify.conn_id, p_data->notify.handle);
      }

      break;

    case BTA_GATTC_OPEN_EVT:
      instance->OnGattConnected(p_data->open.status, p_data->open.conn_id, p_data->open.client_if,
                                p_data->open.remote_bda, p_data->open.transport, p_data->open.mtu);
      break;

    case BTA_GATTC_ENC_CMPL_CB_EVT: {
      tBTM_STATUS encryption_status;
      if (BTM_IsEncrypted(p_data->enc_cmpl.remote_bda, BT_TRANSPORT_LE)) {
        encryption_status = tBTM_STATUS::BTM_SUCCESS;
      } else {
        encryption_status = tBTM_STATUS::BTM_FAILED_ON_SECURITY;
      }
      instance->OnEncryptionComplete(p_data->enc_cmpl.remote_bda, encryption_status);
    } break;

    case BTA_GATTC_CLOSE_EVT:
      instance->OnGattDisconnected(p_data->close.conn_id, p_data->close.client_if,
                                   p_data->close.remote_bda, p_data->close.reason);
      break;

    case BTA_GATTC_SEARCH_CMPL_EVT:
      instance->OnServiceSearchComplete(p_data->search_cmpl.conn_id, p_data->search_cmpl.status);
      break;

    case BTA_GATTC_SRVC_DISC_DONE_EVT:
      instance->OnGattServiceDiscoveryDone(p_data->service_discovery_done.remote_bda);
      break;

    case BTA_GATTC_SRVC_CHG_EVT:
      instance->OnServiceChangeEvent(p_data->service_changed.remote_bda);
      break;
    case BTA_GATTC_CFG_MTU_EVT:
      instance->OnMtuChanged(p_data->cfg_mtu.conn_id, p_data->cfg_mtu.mtu);
      break;
    case BTA_GATTC_PHY_UPDATE_EVT:
      instance->OnPhyUpdate(p_data->phy_update.conn_id, p_data->phy_update.tx_phy,
                            p_data->phy_update.rx_phy, p_data->phy_update.status);
      break;
    default:
      break;
  }
}

class LeAudioStateMachineHciCallbacksImpl : public CigCallbacks {
public:
  void OnCigEvent(uint8_t event, void* data) override {
    if (instance) {
      instance->IsoCigEventsCb(event, data);
    }
  }

  void OnCisEvent(uint8_t event, void* data) override {
    if (instance) {
      instance->IsoCisEventsCb(event, data);
    }
  }

  void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) override {
    if (instance) {
      instance->IsoSetupIsoDataPathCb(status, conn_handle, cig_id);
    }
  }

  void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) override {
    if (instance) {
      instance->IsoRemoveIsoDataPathCb(status, conn_handle, cig_id);
    }
  }

  void OnIsoLinkQualityRead(uint8_t conn_handle, uint8_t cig_id, uint32_t txUnackedPackets,
                            uint32_t txFlushedPackets, uint32_t txLastSubeventPackets,
                            uint32_t retransmittedPackets, uint32_t crcErrorPackets,
                            uint32_t rxUnreceivedPackets, uint32_t duplicatePackets) {
    if (instance) {
      instance->IsoLinkQualityReadCb(conn_handle, cig_id, txUnackedPackets, txFlushedPackets,
                                     txLastSubeventPackets, retransmittedPackets, crcErrorPackets,
                                     rxUnreceivedPackets, duplicatePackets);
    }
  }
};

LeAudioStateMachineHciCallbacksImpl stateMachineHciCallbacksImpl;

class CallbacksImpl : public LeAudioGroupStateMachine::Callbacks {
public:
  void StatusReportCb(int group_id, GroupStreamStatus status) override {
    if (instance) {
      instance->OnStateMachineStatusReportCb(group_id, status);
    }
  }

  void OnStateTransitionTimeout(int group_id) override {
    if (instance) {
      instance->OnLeAudioDeviceSetStateTimeout(group_id);
    }
  }

  void OnUpdatedCisConfiguration(int group_id, uint8_t direction) {
    if (instance) {
      instance->OnUpdatedCisConfiguration(group_id, direction);
    }
  }
};

CallbacksImpl stateMachineCallbacksImpl;

class SourceCallbacksImpl : public LeAudioSourceAudioHalClient::Callbacks {
public:
  void OnAudioDataReady(const std::vector<uint8_t>& data) override {
    if (instance) {
      instance->OnAudioDataReady(data);
    }
  }
  void OnAudioSuspend(void) override {
    if (instance) {
      instance->OnLocalAudioSourceSuspend();
    }
  }

  void OnAudioResume(void) override {
    if (instance) {
      instance->OnLocalAudioSourceResume();
    }
  }

  void OnAudioMetadataUpdate(std::vector<struct playback_track_metadata_v7> source_metadata,
                             DsaMode dsa_mode) override {
    if (instance) {
      instance->OnLocalAudioSourceMetadataUpdate(source_metadata, dsa_mode);
    }
  }
};

class SinkCallbacksImpl : public LeAudioSinkAudioHalClient::Callbacks {
public:
  void OnAudioSuspend(void) override {
    if (instance) {
      instance->OnLocalAudioSinkSuspend();
    }
  }
  void OnAudioResume(void) override {
    if (instance) {
      instance->OnLocalAudioSinkResume();
    }
  }

  void OnAudioMetadataUpdate(std::vector<record_track_metadata_v7> sink_metadata) override {
    if (instance) {
      instance->OnLocalAudioSinkMetadataUpdate(sink_metadata);
    }
  }
};

SourceCallbacksImpl audioSinkReceiverImpl;
SinkCallbacksImpl audioSourceReceiverImpl;

class DeviceGroupsCallbacksImpl : public DeviceGroupsCallbacks {
public:
  void OnGroupAdded(const RawAddress& address, const bluetooth::Uuid& uuid, int group_id) override {
    if (instance) {
      instance->OnGroupAddedCb(address, uuid, group_id);
    }
  }
  void OnGroupMemberAdded(const RawAddress& address, int group_id) override {
    if (instance) {
      instance->OnGroupMemberAddedCb(address, group_id);
    }
  }
  void OnGroupMemberRemoved(const RawAddress& address, int group_id) override {
    if (instance) {
      instance->OnGroupMemberRemovedCb(address, group_id);
    }
  }
  void OnGroupRemoved(const bluetooth::Uuid& /*uuid*/,
                      int /*group_id*/) { /* to implement if needed */ }
  void OnGroupAddFromStorage(const RawAddress& /*address*/, const bluetooth::Uuid& /*uuid*/,
                             int /*group_id*/) {
    /* to implement if needed */
  }
};

class DeviceGroupsCallbacksImpl;
DeviceGroupsCallbacksImpl deviceGroupsCallbacksImpl;

}  // namespace

void LeAudioClient::AddFromStorage(
        const RawAddress& addr, bool autoconnect, std::optional<int> sink_audio_location,
        std::optional<int> source_audio_location, int sink_supported_context_types,
        int source_supported_context_types, const std::vector<uint8_t>& handles,
        const std::vector<uint8_t>& sink_pacs, const std::vector<uint8_t>& source_pacs,
        const std::vector<uint8_t>& ases, const std::vector<uint8_t>& gmap) {
  if (!instance) {
    log::error("Not initialized yet");
    return;
  }

  instance->AddFromStorage(addr, autoconnect, sink_audio_location, source_audio_location,
                           sink_supported_context_types, source_supported_context_types, handles,
                           sink_pacs, source_pacs, ases, gmap);
}

bool LeAudioClient::GetHandlesForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
  if (!instance) {
    log::error("Not initialized yet");
    return false;
  }

  return instance->GetHandlesForStorage(addr, out);
}

bool LeAudioClient::GetSinkPacsForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
  if (!instance) {
    log::error("Not initialized yet");
    return false;
  }

  return instance->GetSinkPacsForStorage(addr, out);
}

bool LeAudioClient::GetSourcePacsForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
  if (!instance) {
    log::error("Not initialized yet");
    return false;
  }

  return instance->GetSourcePacsForStorage(addr, out);
}

bool LeAudioClient::GetAsesForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
  if (!instance) {
    log::error("Not initialized yet");
    return false;
  }

  return instance->GetAsesForStorage(addr, out);
}

bool LeAudioClient::GetGmapForStorage(const RawAddress& addr, std::vector<uint8_t>& out) {
  if (!instance) {
    log::error("Not initialized yet");
    return false;
  }

  return instance->GetGmapForStorage(addr, out);
}

bool LeAudioClient::IsLeAudioClientRunning(void) { return instance != nullptr; }

bool LeAudioClient::IsLeAudioClientInStreaming(void) {
  if (!instance) {
    return false;
  }
  return instance->IsInStreaming();
}

LeAudioClient* LeAudioClient::Get() {
  log::assert_that(instance != nullptr, "assert failed: instance != nullptr");
  return instance;
}

/* Initializer of main le audio implementation class and its instance */
void LeAudioClient::Initialize(
        bluetooth::le_audio::LeAudioClientCallbacks* callbacks_, base::Closure initCb,
        base::Callback<bool()> hal_2_1_verifier,
        const std::vector<bluetooth::le_audio::btle_audio_codec_config_t>& offloading_preference) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    log::error("Already initialized");
    return;
  }

  if (!bluetooth::shim::GetController()->SupportsBleConnectedIsochronousStreamCentral() &&
      !bluetooth::shim::GetController()->SupportsBleConnectedIsochronousStreamPeripheral()) {
    log::error("Controller reports no ISO support. LeAudioClient Init aborted.");
    return;
  }

  log::assert_that(std::move(hal_2_1_verifier).Run(),
                   "LE Audio Client requires Bluetooth Audio HAL V2.1 at least. Either "
                   "disable LE Audio Profile, or update your HAL");

  IsoManager::GetInstance()->Start();

  audioSinkReceiver = &audioSinkReceiverImpl;
  audioSourceReceiver = &audioSourceReceiverImpl;
  stateMachineHciCallbacks = &stateMachineHciCallbacksImpl;
  stateMachineCallbacks = &stateMachineCallbacksImpl;
  device_group_callbacks = &deviceGroupsCallbacksImpl;
  instance = new LeAudioClientImpl(callbacks_, stateMachineCallbacks, initCb);

  IsoManager::GetInstance()->RegisterCigCallbacks(stateMachineHciCallbacks);
  CodecManager::GetInstance()->Start(offloading_preference);
  ContentControlIdKeeper::GetInstance()->Start();

  callbacks_->OnInitialized();

  auto cm = CodecManager::GetInstance();
  callbacks_->OnAudioLocalCodecCapabilities(cm->GetLocalAudioInputCodecCapa(),
                                            cm->GetLocalAudioOutputCodecCapa());

  if (GmapServer::IsGmapServerEnabled()) {
    std::bitset<8> UGG_feature = GmapServer::GetUGGFeature();

    auto input_capabilities = cm->GetLocalAudioOutputCodecCapa();
    for (auto& capa : input_capabilities) {
      if (capa.sample_rate == bluetooth::le_audio::LE_AUDIO_SAMPLE_RATE_INDEX_48000HZ) {
        UGG_feature |= static_cast<uint8_t>(
                bluetooth::gmap::UGGFeatureBitMask::NinetySixKbpsSourceFeatureSupport);
        break;
      }
    }

    auto output_capabilities = cm->GetLocalAudioOutputCodecCapa();
    for (auto& capa : output_capabilities) {
      if (capa.channel_count > bluetooth::le_audio::LE_AUDIO_CHANNEL_COUNT_INDEX_1) {
        UGG_feature |=
                static_cast<uint8_t>(bluetooth::gmap::UGGFeatureBitMask::MultiplexFeatureSupport);
        break;
      }
    }
    GmapServer::Initialize(UGG_feature);
  }
}

void LeAudioClient::DebugDump(int fd) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  DeviceGroups::DebugDump(fd);
  GmapServer::DebugDump(fd);

  dprintf(fd, "LeAudio Manager: \n");
  if (instance) {
    instance->Dump(fd);
  } else {
    dprintf(fd, "  Not initialized \n");
  }

  LeAudioSinkAudioHalClient::DebugDump(fd);
  LeAudioSourceAudioHalClient::DebugDump(fd);
  IsoManager::GetInstance()->Dump(fd);
  LeAudioLogHistory::DebugDump(fd);
  dprintf(fd, "\n");
}

void LeAudioClient::Cleanup(void) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (!instance) {
    log::error("Not initialized");
    return;
  }

  LeAudioClientImpl* ptr = instance;
  instance = nullptr;
  ptr->Cleanup();
  delete ptr;
  ptr = nullptr;

  CodecManager::GetInstance()->Stop();
  ContentControlIdKeeper::GetInstance()->Stop();
  LeAudioGroupStateMachine::Cleanup();

  if (!LeAudioBroadcaster::IsLeAudioBroadcasterRunning()) {
    IsoManager::GetInstance()->Stop();
  }

  bluetooth::le_audio::MetricsCollector::Get()->Flush();
}

bool LeAudioClient::RegisterIsoDataConsumer(LeAudioIsoDataCallback callback) {
  log::info("ISO data consumer changed");
  iso_data_callback = callback;
  return true;
}
