/*   Copyright 2019 HIMSA II K/S - www.himsa.com
 * Represented by EHIMA - www.ehima.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "BluetoothLeAudioServiceJni"

#include <com_android_bluetooth_flags.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <map>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <string>
#include <type_traits>
#include <vector>

#include "bluetooth/log.h"
#include "bluetooth/types/address.h"
#include "com_android_bluetooth.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_le_audio.h"
#include "jni.h"
#include "nativehelper/JNIHelp.h"
#include "nativehelper/scoped_local_ref.h"

using bluetooth::le_audio::BroadcastId;
using bluetooth::le_audio::BroadcastState;
using bluetooth::le_audio::btle_audio_bits_per_sample_index_t;
using bluetooth::le_audio::btle_audio_channel_count_index_t;
using bluetooth::le_audio::btle_audio_codec_config_t;
using bluetooth::le_audio::btle_audio_codec_index_t;
using bluetooth::le_audio::btle_audio_frame_duration_index_t;
using bluetooth::le_audio::btle_audio_sample_rate_index_t;
using bluetooth::le_audio::ConnectionState;
using bluetooth::le_audio::GroupNodeStatus;
using bluetooth::le_audio::GroupStatus;
using bluetooth::le_audio::GroupStreamStatus;
using bluetooth::le_audio::LeAudioClientCallbacks;
using bluetooth::le_audio::LeAudioClientInterface;
using bluetooth::le_audio::UnicastMonitorModeStatus;

namespace android {
static jmethodID method_onInitialized;
static jmethodID method_onConnectionStateChanged;
static jmethodID method_onGroupStatus;
static jmethodID method_onGroupNodeStatus;
static jmethodID method_onAudioConf;
static jmethodID method_onSinkAudioLocationAvailable;
static jmethodID method_onAudioLocalCodecCapabilities;
static jmethodID method_onAudioGroupCurrentCodecConf;
static jmethodID method_onAudioGroupSelectableCodecConf;
static jmethodID method_onHealthBasedRecommendationAction;
static jmethodID method_onHealthBasedGroupRecommendationAction;
static jmethodID method_onUnicastMonitorModeStatus;
static jmethodID method_onGroupStreamStatus;

static struct {
  jclass clazz;
  jmethodID constructor;
  jmethodID constructor_with_codec_id;
  jmethodID getCodecType;
  jmethodID getCodecId;
  jmethodID getSampleRate;
  jmethodID getBitsPerSample;
  jmethodID getChannelCount;
  jmethodID getFrameDuration;
  jmethodID getOctetsPerFrame;
  jmethodID getCodecPriority;
} android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id;

static struct {
  jclass clazz;
  jmethodID constructor;
  jmethodID getCodecType;
  jmethodID getSampleRate;
  jmethodID getBitsPerSample;
  jmethodID getChannelCount;
  jmethodID getFrameDuration;
  jmethodID getOctetsPerFrame;
  jmethodID getCodecPriority;
} android_bluetooth_BluetoothLeAudioCodecConfig;

static LeAudioClientInterface* sLeAudioClientInterface = nullptr;
static std::shared_timed_mutex interface_mutex;

static jobject mCallbacksObj = nullptr;
static std::shared_timed_mutex callbacks_mutex;

static jclass class_LeAudioNativeInterface;

static jobject prepareCodecConfigObj(JNIEnv* env, btle_audio_codec_config_t codecConfig) {
  log::info(
          "ct: {}, codec_priority: {}, sample_rate: {}, bits_per_sample: {}, "
          "channel_count: {}, frame_duration: {}, octets_per_frame: {}",
          codecConfig.codec_type, codecConfig.codec_priority, codecConfig.sample_rate,
          codecConfig.bits_per_sample, codecConfig.channel_count, codecConfig.frame_duration,
          codecConfig.octets_per_frame);

  jobject codecConfigObj;

  if (com_android_bluetooth_flags_leaudio_codec_id_support()) {
    codecConfigObj = env->NewObject(
            android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.clazz,
            android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.constructor_with_codec_id,
            (jint)codecConfig.codec_type, (jlong)codecConfig.codec_id,
            (jint)codecConfig.codec_priority, (jint)codecConfig.sample_rate,
            (jint)codecConfig.bits_per_sample, (jint)codecConfig.channel_count,
            (jint)codecConfig.frame_duration, (jint)codecConfig.octets_per_frame, 0, 0);
  } else {
    codecConfigObj = env->NewObject(
            android_bluetooth_BluetoothLeAudioCodecConfig.clazz,
            android_bluetooth_BluetoothLeAudioCodecConfig.constructor, (jint)codecConfig.codec_type,
            (jint)codecConfig.codec_priority, (jint)codecConfig.sample_rate,
            (jint)codecConfig.bits_per_sample, (jint)codecConfig.channel_count,
            (jint)codecConfig.frame_duration, (jint)codecConfig.octets_per_frame, 0, 0);
  }
  return codecConfigObj;
}

static jobjectArray prepareArrayOfCodecConfigs(
        JNIEnv* env, std::vector<btle_audio_codec_config_t> codecConfigs) {
  jsize i = 0;
  jobjectArray CodecConfigArray;
  if (com_android_bluetooth_flags_leaudio_codec_id_support()) {
    CodecConfigArray = env->NewObjectArray(
            (jsize)codecConfigs.size(),
            android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.clazz, nullptr);
  } else {
    CodecConfigArray =
            env->NewObjectArray((jsize)codecConfigs.size(),
                                android_bluetooth_BluetoothLeAudioCodecConfig.clazz, nullptr);
  }

  for (auto const& cap : codecConfigs) {
    jobject Obj = prepareCodecConfigObj(env, cap);

    env->SetObjectArrayElement(CodecConfigArray, i++, Obj);
    env->DeleteLocalRef(Obj);
  }

  return CodecConfigArray;
}

class LeAudioClientCallbacksImpl : public LeAudioClientCallbacks {
public:
  ~LeAudioClientCallbacksImpl() = default;

  void OnInitialized(void) override {
    log::info("");
    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onInitialized);
  }

  void OnConnectionState(ConnectionState state, const RawAddress& bd_addr) override {
    log::info("state:{}, addr: {}", int(state), bd_addr.ToRedactedStringForLogging());

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> jaddr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectionStateChanged, (jint)state,
                                 jaddr.get());
  }

  void OnGroupStatus(int group_id, GroupStatus group_status) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGroupStatus, (jint)group_id,
                                 (jint)group_status);
  }

  void OnGroupNodeStatus(const RawAddress& bd_addr, int group_id,
                         GroupNodeStatus node_status) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> jaddr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGroupNodeStatus, jaddr.get(),
                                 (jint)group_id, (jint)node_status);
  }

  void OnAudioConf(uint8_t direction, int group_id,
                   std::optional<std::bitset<32>> sink_audio_location,
                   std::optional<std::bitset<32>> source_audio_location,
                   uint16_t avail_cont) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    jint jni_sink_audio_location = sink_audio_location ? sink_audio_location->to_ulong() : -1;
    jint jni_source_audio_location = source_audio_location ? source_audio_location->to_ulong() : -1;
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAudioConf, (jint)direction, (jint)group_id,
                                 jni_sink_audio_location, jni_source_audio_location,
                                 (jint)avail_cont);
  }

  void OnSinkAudioLocationAvailable(const RawAddress& bd_addr,
                                    std::optional<std::bitset<32>> sink_audio_location) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> jaddr = addressToJByteArray(sCallbackEnv, bd_addr);
    jint jni_sink_audio_location = sink_audio_location ? sink_audio_location->to_ulong() : -1;
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onSinkAudioLocationAvailable, jaddr.get(),
                                 jni_sink_audio_location);
  }

  void OnAudioLocalCodecCapabilities(
          std::vector<btle_audio_codec_config_t> local_input_capa_codec_conf,
          std::vector<btle_audio_codec_config_t> local_output_capa_codec_conf) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    jobject localInputCapCodecConfigArray =
            prepareArrayOfCodecConfigs(sCallbackEnv.get(), local_input_capa_codec_conf);

    jobject localOutputCapCodecConfigArray =
            prepareArrayOfCodecConfigs(sCallbackEnv.get(), local_output_capa_codec_conf);

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAudioLocalCodecCapabilities,
                                 localInputCapCodecConfigArray, localOutputCapCodecConfigArray);
  }

  void OnAudioGroupCurrentCodecConf(int group_id, btle_audio_codec_config_t input_codec_conf,
                                    btle_audio_codec_config_t output_codec_conf) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    jobject inputCodecConfigObj = prepareCodecConfigObj(sCallbackEnv.get(), input_codec_conf);
    jobject outputCodecConfigObj = prepareCodecConfigObj(sCallbackEnv.get(), output_codec_conf);

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAudioGroupCurrentCodecConf, (jint)group_id,
                                 inputCodecConfigObj, outputCodecConfigObj);
  }

  void OnAudioGroupSelectableCodecConf(
          int group_id, std::vector<btle_audio_codec_config_t> input_selectable_codec_conf,
          std::vector<btle_audio_codec_config_t> output_selectable_codec_conf) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    jobject inputSelectableCodecConfigArray =
            prepareArrayOfCodecConfigs(sCallbackEnv.get(), input_selectable_codec_conf);
    jobject outputSelectableCodecConfigArray =
            prepareArrayOfCodecConfigs(sCallbackEnv.get(), output_selectable_codec_conf);

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAudioGroupSelectableCodecConf,
                                 (jint)group_id, inputSelectableCodecConfigArray,
                                 outputSelectableCodecConfigArray);
  }

  void OnHealthBasedRecommendationAction(
          const RawAddress& bd_addr,
          bluetooth::le_audio::LeAudioHealthBasedAction action) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> jaddr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onHealthBasedRecommendationAction,
                                 jaddr.get(), (jint)action);
  }

  void OnHealthBasedGroupRecommendationAction(
          int group_id, bluetooth::le_audio::LeAudioHealthBasedAction action) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onHealthBasedGroupRecommendationAction,
                                 (jint)group_id, (jint)action);
  }

  void OnUnicastMonitorModeStatus(uint8_t direction, UnicastMonitorModeStatus status) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onUnicastMonitorModeStatus, (jint)direction,
                                 (jint)status);
  }

  void OnGroupStreamStatus(int group_id, GroupStreamStatus group_stream_status) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGroupStreamStatus, (jint)group_id,
                                 (jint)group_stream_status);
  }
};

static LeAudioClientCallbacksImpl sLeAudioClientCallbacks;

static std::vector<btle_audio_codec_config_t> prepareCodecPreferences(
        JNIEnv* env, jobject /* object */, jobjectArray codecConfigArray) {
  std::vector<btle_audio_codec_config_t> codec_preferences;

  int numConfigs = env->GetArrayLength(codecConfigArray);
  for (int i = 0; i < numConfigs; i++) {
    jobject jcodecConfig = env->GetObjectArrayElement(codecConfigArray, i);
    if (jcodecConfig == nullptr) {
      continue;
    }
    jint codecType = 0;
    jlong codecId = 0;
    if (com_android_bluetooth_flags_leaudio_codec_id_support()) {
      if (!env->IsInstanceOf(jcodecConfig,
                             android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.clazz)) {
        log::error("Invalid BluetoothLeAudioCodecConfig instance");
        continue;
      }
      codecType = env->CallIntMethod(
              jcodecConfig,
              android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecType);
      codecId = env->CallLongMethod(
              jcodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecId);
    } else {
      if (!env->IsInstanceOf(jcodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.clazz)) {
        log::error("Invalid BluetoothLeAudioCodecConfig instance");
        continue;
      }
      codecType = env->CallIntMethod(jcodecConfig,
                                     android_bluetooth_BluetoothLeAudioCodecConfig.getCodecType);
    }

    btle_audio_codec_config_t codec_config = {
            .codec_type = static_cast<btle_audio_codec_index_t>(codecType),
            .codec_id = static_cast<uint64_t>(codecId)};

    codec_preferences.push_back(codec_config);
  }
  return codec_preferences;
}

static void initNative(JNIEnv* env, jobject object, jobjectArray codecOffloadingArray) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  jclass tmpControllerInterface =
          env->FindClass("com/android/bluetooth/le_audio/LeAudioNativeInterface");
  class_LeAudioNativeInterface = (jclass)env->NewGlobalRef(tmpControllerInterface);

  if (mCallbacksObj != nullptr) {
    log::info("Cleaning up LeAudio callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  if ((mCallbacksObj = env->NewGlobalRef(object)) == nullptr) {
    log::fatal("Failed to allocate Global Ref for LeAudio Callbacks");
  }

  if (com_android_bluetooth_flags_leaudio_codec_id_support()) {
    android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.clazz = (jclass)env->NewGlobalRef(
            env->FindClass("android/bluetooth/BluetoothLeAudioCodecConfig"));
    if (android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.clazz == nullptr) {
      log::error("Failed to allocate Global Ref for BluetoothLeAudioCodecConfig class");
      return;
    }
  } else {
    android_bluetooth_BluetoothLeAudioCodecConfig.clazz = (jclass)env->NewGlobalRef(
            env->FindClass("android/bluetooth/BluetoothLeAudioCodecConfig"));
    if (android_bluetooth_BluetoothLeAudioCodecConfig.clazz == nullptr) {
      log::error("Failed to allocate Global Ref for BluetoothLeAudioCodecConfig class");
      return;
    }
  }

  sLeAudioClientInterface =
          (LeAudioClientInterface*)btInf->get_profile_interface(BT_PROFILE_LE_AUDIO_ID);
  if (sLeAudioClientInterface == nullptr) {
    log::error("Failed to get Bluetooth LeAudio Interface");
    return;
  }

  std::vector<btle_audio_codec_config_t> codec_offloading =
          prepareCodecPreferences(env, object, codecOffloadingArray);

  sLeAudioClientInterface->Initialize(&sLeAudioClientCallbacks, codec_offloading);
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sLeAudioClientInterface != nullptr) {
    sLeAudioClientInterface->Cleanup();
    sLeAudioClientInterface = nullptr;
  }

  if (com_android_bluetooth_flags_leaudio_codec_id_support()) {
    env->DeleteGlobalRef(android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.clazz);
    android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.clazz = nullptr;
  } else {
    env->DeleteGlobalRef(android_bluetooth_BluetoothLeAudioCodecConfig.clazz);
    android_bluetooth_BluetoothLeAudioCodecConfig.clazz = nullptr;
  }
  if (mCallbacksObj != nullptr) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }
}

static jboolean connectLeAudioNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sLeAudioClientInterface->Connect(bd_addr);
  return JNI_TRUE;
}

static jboolean disconnectLeAudioNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sLeAudioClientInterface->Disconnect(bd_addr);
  return JNI_TRUE;
}

static jboolean setEnableStateNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                     jboolean enabled) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sLeAudioClientInterface->SetEnableState(bd_addr, enabled);
  return JNI_TRUE;
}

static jboolean groupAddNodeNative(JNIEnv* env, jobject /* object */, jint group_id,
                                   jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sLeAudioClientInterface->GroupAddNode(group_id, bd_addr);
  return JNI_TRUE;
}

static jboolean groupRemoveNodeNative(JNIEnv* env, jobject /* object */, jint group_id,
                                      jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sLeAudioClientInterface->GroupRemoveNode(group_id, bd_addr);
  return JNI_TRUE;
}

static void groupSetActiveNative(JNIEnv* /* env */, jobject /* object */, jint group_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);

  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return;
  }

  sLeAudioClientInterface->GroupSetActive(group_id);
}

static void setCodecConfigPreferenceWithCodecIdNative(JNIEnv* env, jint group_id,
                                                      jobject inputCodecConfig,
                                                      jobject outputCodecConfig) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);

  if (!env->IsInstanceOf(inputCodecConfig,
                         android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.clazz) ||
      !env->IsInstanceOf(outputCodecConfig,
                         android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.clazz)) {
    log::error("Invalid BluetoothLeAudioCodecConfig instance");
    return;
  }

  jint inputCodecType = env->CallIntMethod(
          inputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecType);

  jlong inputCodecId = env->CallLongMethod(
          inputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecId);

  jint inputSampleRate = env->CallIntMethod(
          inputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getSampleRate);

  jint inputBitsPerSample = env->CallIntMethod(
          inputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getBitsPerSample);

  jint inputChannelCount = env->CallIntMethod(
          inputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getChannelCount);

  jint inputFrameDuration = env->CallIntMethod(
          inputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getFrameDuration);

  jint inputOctetsPerFrame = env->CallIntMethod(
          inputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getOctetsPerFrame);

  jint inputCodecPriority = env->CallIntMethod(
          inputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecPriority);

  btle_audio_codec_config_t input_codec_config = {
          .codec_type = static_cast<btle_audio_codec_index_t>(inputCodecType),
          .sample_rate = static_cast<btle_audio_sample_rate_index_t>(inputSampleRate),
          .bits_per_sample = static_cast<btle_audio_bits_per_sample_index_t>(inputBitsPerSample),
          .channel_count = static_cast<btle_audio_channel_count_index_t>(inputChannelCount),
          .frame_duration = static_cast<btle_audio_frame_duration_index_t>(inputFrameDuration),
          .octets_per_frame = static_cast<uint16_t>(inputOctetsPerFrame),
          .codec_priority = static_cast<int32_t>(inputCodecPriority),
          .codec_id = static_cast<uint64_t>(inputCodecId),
  };

  jint outputCodecType = env->CallIntMethod(
          outputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecType);

  jlong outputCodecId = env->CallLongMethod(
          outputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecId);

  jint outputSampleRate = env->CallIntMethod(
          outputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getSampleRate);

  jint outputBitsPerSample = env->CallIntMethod(
          outputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getBitsPerSample);

  jint outputChannelCount = env->CallIntMethod(
          outputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getChannelCount);

  jint outputFrameDuration = env->CallIntMethod(
          outputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getFrameDuration);

  jint outputOctetsPerFrame = env->CallIntMethod(
          outputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getOctetsPerFrame);

  jint outputCodecPriority = env->CallIntMethod(
          outputCodecConfig,
          android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecPriority);

  btle_audio_codec_config_t output_codec_config = {
          .codec_type = static_cast<btle_audio_codec_index_t>(outputCodecType),
          .sample_rate = static_cast<btle_audio_sample_rate_index_t>(outputSampleRate),
          .bits_per_sample = static_cast<btle_audio_bits_per_sample_index_t>(outputBitsPerSample),
          .channel_count = static_cast<btle_audio_channel_count_index_t>(outputChannelCount),
          .frame_duration = static_cast<btle_audio_frame_duration_index_t>(outputFrameDuration),
          .octets_per_frame = static_cast<uint16_t>(outputOctetsPerFrame),
          .codec_priority = static_cast<int32_t>(outputCodecPriority),
          .codec_id = static_cast<uint64_t>(outputCodecId),
  };

  sLeAudioClientInterface->SetCodecConfigPreference(group_id, input_codec_config,
                                                    output_codec_config);
}

static void setCodecConfigPreferenceNative(JNIEnv* env, jobject /* object */, jint group_id,
                                           jobject inputCodecConfig, jobject outputCodecConfig) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);

  if (com_android_bluetooth_flags_leaudio_codec_id_support()) {
    setCodecConfigPreferenceWithCodecIdNative(env, group_id, inputCodecConfig, outputCodecConfig);
    return;
  }

  if (!env->IsInstanceOf(inputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.clazz) ||
      !env->IsInstanceOf(outputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.clazz)) {
    log::error("Invalid BluetoothLeAudioCodecConfig instance");
    return;
  }

  jint inputCodecType = env->CallIntMethod(
          inputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getCodecType);

  jint inputSampleRate = env->CallIntMethod(
          inputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getSampleRate);

  jint inputBitsPerSample = env->CallIntMethod(
          inputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getBitsPerSample);

  jint inputChannelCount = env->CallIntMethod(
          inputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getChannelCount);

  jint inputFrameDuration = env->CallIntMethod(
          inputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getFrameDuration);

  jint inputOctetsPerFrame = env->CallIntMethod(
          inputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getOctetsPerFrame);

  jint inputCodecPriority = env->CallIntMethod(
          inputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getCodecPriority);

  btle_audio_codec_config_t input_codec_config = {
          .codec_type = static_cast<btle_audio_codec_index_t>(inputCodecType),
          .sample_rate = static_cast<btle_audio_sample_rate_index_t>(inputSampleRate),
          .bits_per_sample = static_cast<btle_audio_bits_per_sample_index_t>(inputBitsPerSample),
          .channel_count = static_cast<btle_audio_channel_count_index_t>(inputChannelCount),
          .frame_duration = static_cast<btle_audio_frame_duration_index_t>(inputFrameDuration),
          .octets_per_frame = static_cast<uint16_t>(inputOctetsPerFrame),
          .codec_priority = static_cast<int32_t>(inputCodecPriority),
          .codec_id = 0,
  };

  jint outputCodecType = env->CallIntMethod(
          outputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getCodecType);

  jint outputSampleRate = env->CallIntMethod(
          outputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getSampleRate);

  jint outputBitsPerSample = env->CallIntMethod(
          outputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getBitsPerSample);

  jint outputChannelCount = env->CallIntMethod(
          outputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getChannelCount);

  jint outputFrameDuration = env->CallIntMethod(
          outputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getFrameDuration);

  jint outputOctetsPerFrame = env->CallIntMethod(
          outputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getOctetsPerFrame);

  jint outputCodecPriority = env->CallIntMethod(
          outputCodecConfig, android_bluetooth_BluetoothLeAudioCodecConfig.getCodecPriority);

  btle_audio_codec_config_t output_codec_config = {
          .codec_type = static_cast<btle_audio_codec_index_t>(outputCodecType),
          .sample_rate = static_cast<btle_audio_sample_rate_index_t>(outputSampleRate),
          .bits_per_sample = static_cast<btle_audio_bits_per_sample_index_t>(outputBitsPerSample),
          .channel_count = static_cast<btle_audio_channel_count_index_t>(outputChannelCount),
          .frame_duration = static_cast<btle_audio_frame_duration_index_t>(outputFrameDuration),
          .octets_per_frame = static_cast<uint16_t>(outputOctetsPerFrame),
          .codec_priority = static_cast<int32_t>(outputCodecPriority),
          .codec_id = 0,
  };

  sLeAudioClientInterface->SetCodecConfigPreference(group_id, input_codec_config,
                                                    output_codec_config);
}

static void setCcidInformationNative(JNIEnv* /* env */, jobject /* object */, jint ccid,
                                     jint contextType) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return;
  }

  sLeAudioClientInterface->SetCcidInformation(ccid, contextType);
}

static void setInCallNative(JNIEnv* /* env */, jobject /* object */, jboolean inCall) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return;
  }

  sLeAudioClientInterface->SetInCall(inCall);
}

static void setAllowlistFlagNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                   jboolean allowed) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sLeAudioClientInterface->SetAllowlistFlag(bd_addr, allowed);
}

static void setUnicastMonitorModeNative(JNIEnv* /* env */, jobject /* object */,
                                        jint local_directions, jboolean enable) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return;
  }

  sLeAudioClientInterface->SetUnicastMonitorMode(local_directions, enable);
}

static void sendAudioProfilePreferencesNative(JNIEnv* /* env */, jobject /* object */, jint groupId,
                                              jboolean isOutputPreferenceLeAudio,
                                              jboolean isDuplexPreferenceLeAudio) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return;
  }

  sLeAudioClientInterface->SendAudioProfilePreferences(groupId, isOutputPreferenceLeAudio,
                                                       isDuplexPreferenceLeAudio);
}

static void setGroupAllowedContextMaskNative(JNIEnv* /* env */, jobject /* object */, jint groupId,
                                             jint sinkContextTypes, jint sourceContextTypes) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return;
  }

  log::info("group_id: {}, sink context types: {}, source context types: {}", groupId,
            sinkContextTypes, sourceContextTypes);

  sLeAudioClientInterface->SetGroupAllowedContextMask(groupId, sinkContextTypes,
                                                      sourceContextTypes);
}

static void groupConfirmActiveNative(JNIEnv* /* env */, jobject /* object */, jint group_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);

  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return;
  }

  sLeAudioClientInterface->GroupConfirmActive(group_id);
}

static void setInGameNative(JNIEnv* /* env */, jobject /* object */, jboolean in_game) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);

  if (!sLeAudioClientInterface) {
    log::error("Failed to get the Bluetooth LeAudio Interface");
    return;
  }

  sLeAudioClientInterface->SetInGame(in_game);
}

int register_com_android_bluetooth_le_audio(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative", "([Landroid/bluetooth/BluetoothLeAudioCodecConfig;)V", (void*)initNative},
          {"cleanupNative", "()V", (void*)cleanupNative},
          {"connectLeAudioNative", "([B)Z", (void*)connectLeAudioNative},
          {"disconnectLeAudioNative", "([B)Z", (void*)disconnectLeAudioNative},
          {"setEnableStateNative", "([BZ)Z", (void*)setEnableStateNative},
          {"groupAddNodeNative", "(I[B)Z", (void*)groupAddNodeNative},
          {"groupRemoveNodeNative", "(I[B)Z", (void*)groupRemoveNodeNative},
          {"groupSetActiveNative", "(I)V", (void*)groupSetActiveNative},
          {"setCodecConfigPreferenceNative",
           "(ILandroid/bluetooth/BluetoothLeAudioCodecConfig;"
           "Landroid/bluetooth/BluetoothLeAudioCodecConfig;)V",
           (void*)setCodecConfigPreferenceNative},
          {"setCcidInformationNative", "(II)V", (void*)setCcidInformationNative},
          {"setInCallNative", "(Z)V", (void*)setInCallNative},
          {"setAllowlistFlagNative", "([BZ)V", (void*)setAllowlistFlagNative},
          {"setUnicastMonitorModeNative", "(IZ)V", (void*)setUnicastMonitorModeNative},
          {"sendAudioProfilePreferencesNative", "(IZZ)V", (void*)sendAudioProfilePreferencesNative},
          {"setGroupAllowedContextMaskNative", "(III)V", (void*)setGroupAllowedContextMaskNative},
          {"groupConfirmActiveNative", "(I)V", (void*)groupConfirmActiveNative},
          {"setInGameNative", "(Z)V", (void*)setInGameNative},
  };

  const int result = REGISTER_NATIVE_METHODS(
          env, "com/android/bluetooth/le_audio/LeAudioNativeInterface", methods);
  if (result != 0) {
    return result;
  }

  const JNIJavaMethod javaMethods[] = {
          {"onGroupStatus", "(II)V", &method_onGroupStatus},
          {"onGroupNodeStatus", "([BII)V", &method_onGroupNodeStatus},
          {"onAudioConf", "(IIIII)V", &method_onAudioConf},
          {"onSinkAudioLocationAvailable", "([BI)V", &method_onSinkAudioLocationAvailable},
          {"onInitialized", "()V", &method_onInitialized},
          {"onConnectionStateChanged", "(I[B)V", &method_onConnectionStateChanged},
          {"onAudioLocalCodecCapabilities",
           "([Landroid/bluetooth/BluetoothLeAudioCodecConfig;"
           "[Landroid/bluetooth/BluetoothLeAudioCodecConfig;)V",
           &method_onAudioLocalCodecCapabilities},
          {"onAudioGroupCurrentCodecConf",
           "(ILandroid/bluetooth/BluetoothLeAudioCodecConfig;"
           "Landroid/bluetooth/BluetoothLeAudioCodecConfig;)V",
           &method_onAudioGroupCurrentCodecConf},
          {"onAudioGroupSelectableCodecConf",
           "(I[Landroid/bluetooth/BluetoothLeAudioCodecConfig;"
           "[Landroid/bluetooth/BluetoothLeAudioCodecConfig;)V",
           &method_onAudioGroupSelectableCodecConf},
          {"onHealthBasedRecommendationAction", "([BI)V",
           &method_onHealthBasedRecommendationAction},
          {"onHealthBasedGroupRecommendationAction", "(II)V",
           &method_onHealthBasedGroupRecommendationAction},
          {"onUnicastMonitorModeStatus", "(II)V", &method_onUnicastMonitorModeStatus},
          {"onGroupStreamStatus", "(II)V", &method_onGroupStreamStatus},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/le_audio/LeAudioNativeInterface", javaMethods);

  if (com_android_bluetooth_flags_leaudio_codec_id_support()) {
    const JNIJavaMethod javaLeAudioCodecMethods[] = {
            {"<init>", "(IIIIIIIII)V",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.constructor},
            {"<init>", "(IJIIIIIIII)V",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id
                      .constructor_with_codec_id},
            {"getCodecType", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecType},
            {"getCodecId", "()J",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecId},
            {"getSampleRate", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getSampleRate},
            {"getBitsPerSample", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getBitsPerSample},
            {"getChannelCount", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getChannelCount},
            {"getFrameDuration", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getFrameDuration},
            {"getOctetsPerFrame", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getOctetsPerFrame},
            {"getCodecPriority", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig_with_codec_id.getCodecPriority},
    };
    GET_JAVA_METHODS(env, "android/bluetooth/BluetoothLeAudioCodecConfig", javaLeAudioCodecMethods);
  } else {
    const JNIJavaMethod javaLeAudioCodecMethods[] = {
            {"<init>", "(IIIIIIIII)V", &android_bluetooth_BluetoothLeAudioCodecConfig.constructor},
            {"getCodecType", "()I", &android_bluetooth_BluetoothLeAudioCodecConfig.getCodecType},
            {"getSampleRate", "()I", &android_bluetooth_BluetoothLeAudioCodecConfig.getSampleRate},
            {"getBitsPerSample", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig.getBitsPerSample},
            {"getChannelCount", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig.getChannelCount},
            {"getFrameDuration", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig.getFrameDuration},
            {"getOctetsPerFrame", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig.getOctetsPerFrame},
            {"getCodecPriority", "()I",
             &android_bluetooth_BluetoothLeAudioCodecConfig.getCodecPriority},
    };
    GET_JAVA_METHODS(env, "android/bluetooth/BluetoothLeAudioCodecConfig", javaLeAudioCodecMethods);
  }

  return 0;
}
}  // namespace android
