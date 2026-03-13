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

#define LOG_TAG "bluetooth-a2dp"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_local_ref.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <shared_mutex>
#include <vector>

#include "bt_status.h"
#include "btif/include/btif_av.h"
#include "btif/include/btif_util.h"
#include "com_android_bluetooth.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_av.h"

namespace android {

static struct {
  jfieldID mNativeCallback;
} android_bluetooth_A2dpNativeInterface;

static struct {
  jmethodID onConnectionStateChanged;
  jmethodID onAudioStateChanged;
  jmethodID onCodecConfigChanged;
  jmethodID onAudioDelayReported;
  jmethodID isMandatoryCodecPreferred;
} android_bluetooth_A2dpNativeCallback;

static struct {
  jclass clazz;
  jmethodID constructor;
  jmethodID getCodecType;
  jmethodID getExtendedCodecType;
  jmethodID getCodecPriority;
  jmethodID getSampleRate;
  jmethodID getBitsPerSample;
  jmethodID getChannelMode;
  jmethodID getCodecSpecific1;
  jmethodID getCodecSpecific2;
  jmethodID getCodecSpecific3;
  jmethodID getCodecSpecific4;
} android_bluetooth_BluetoothCodecConfig;

static struct {
  jclass clazz;
  jmethodID constructor;
  jmethodID getCodecId;
} android_bluetooth_BluetoothCodecType;

static std::vector<btav_a2dp_codec_info_t> supported_codecs;
static std::shared_timed_mutex interface_mutex;

static jobject mCallbacksObj = nullptr;
static std::shared_timed_mutex callbacks_mutex;

static jobject newBluetoothCodecType(CallbackEnv& env, btav_a2dp_codec_index_t codec_type) {
  for (auto const& codec : supported_codecs) {
    if (codec.codec_capabilities.codec_type == codec_type) {
      return env->NewObject(android_bluetooth_BluetoothCodecType.clazz,
                            android_bluetooth_BluetoothCodecType.constructor, codec_type,
                            codec.codec_id, env->NewStringUTF(codec.name.c_str()));
    }
  }

  log::warn("unable to create BluetoothCodecStatus from codec type {}", codec_type);
  return nullptr;
}

static void bta2dp_connection_state_callback(const RawAddress& bd_addr,
                                             btav_connection_state_t state,
                                             const btav_error_t& error) {
  log::info("{}: state: {}", bd_addr, dump_av_conn_state(state));

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
    return;
  }

  ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
  sCallbackEnv->CallVoidMethod(mCallbacksObj,
                               android_bluetooth_A2dpNativeCallback.onConnectionStateChanged,
                               addr.get(), (jint)state, (jint)error.error_code);
}

static void bta2dp_audio_state_callback(const RawAddress& bd_addr, btav_audio_state_t state) {
  log::info("{}: state: {}", bd_addr, dump_av_audio_state(state));

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
    return;
  }

  ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
  sCallbackEnv->CallVoidMethod(mCallbacksObj,
                               android_bluetooth_A2dpNativeCallback.onAudioStateChanged, addr.get(),
                               (jint)state);
}

static void bta2dp_audio_config_callback(
        const RawAddress& bd_addr, btav_a2dp_codec_config_t codec_config,
        std::vector<btav_a2dp_codec_config_t> codecs_local_capabilities,
        std::vector<btav_a2dp_codec_config_t> codecs_selectable_capabilities) {
  log::info("{}: codec: {}, local codecs: {}, selectable codecs: {}", bd_addr,
            codec_config.CodecNameStr(),
            btav_a2dp_codec_config_t::PrintCodecs(codecs_local_capabilities),
            btav_a2dp_codec_config_t::PrintCodecs(codecs_selectable_capabilities));

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
    return;
  }

  jobject codecConfigObj = sCallbackEnv->NewObject(
          android_bluetooth_BluetoothCodecConfig.clazz,
          android_bluetooth_BluetoothCodecConfig.constructor,
          newBluetoothCodecType(sCallbackEnv, codec_config.codec_type),
          (jint)codec_config.codec_priority, (jint)codec_config.sample_rate,
          (jint)codec_config.bits_per_sample, (jint)codec_config.channel_mode,
          (jlong)codec_config.codec_specific_1, (jlong)codec_config.codec_specific_2,
          (jlong)codec_config.codec_specific_3, (jlong)codec_config.codec_specific_4);

  jsize i = 0;
  jobjectArray local_capabilities_array =
          sCallbackEnv->NewObjectArray((jsize)codecs_local_capabilities.size(),
                                       android_bluetooth_BluetoothCodecConfig.clazz, nullptr);
  for (auto const& cap : codecs_local_capabilities) {
    jobject capObj = sCallbackEnv->NewObject(
            android_bluetooth_BluetoothCodecConfig.clazz,
            android_bluetooth_BluetoothCodecConfig.constructor,
            newBluetoothCodecType(sCallbackEnv, cap.codec_type), (jint)cap.codec_priority,
            (jint)cap.sample_rate, (jint)cap.bits_per_sample, (jint)cap.channel_mode,
            (jlong)cap.codec_specific_1, (jlong)cap.codec_specific_2, (jlong)cap.codec_specific_3,
            (jlong)cap.codec_specific_4);

    sCallbackEnv->SetObjectArrayElement(local_capabilities_array, i++, capObj);
    sCallbackEnv->DeleteLocalRef(capObj);
  }

  i = 0;
  jobjectArray selectable_capabilities_array =
          sCallbackEnv->NewObjectArray((jsize)codecs_selectable_capabilities.size(),
                                       android_bluetooth_BluetoothCodecConfig.clazz, nullptr);
  for (auto const& cap : codecs_selectable_capabilities) {
    jobject capObj = sCallbackEnv->NewObject(
            android_bluetooth_BluetoothCodecConfig.clazz,
            android_bluetooth_BluetoothCodecConfig.constructor,
            newBluetoothCodecType(sCallbackEnv, cap.codec_type), (jint)cap.codec_priority,
            (jint)cap.sample_rate, (jint)cap.bits_per_sample, (jint)cap.channel_mode,
            (jlong)cap.codec_specific_1, (jlong)cap.codec_specific_2, (jlong)cap.codec_specific_3,
            (jlong)cap.codec_specific_4);
    sCallbackEnv->SetObjectArrayElement(selectable_capabilities_array, i++, capObj);
    sCallbackEnv->DeleteLocalRef(capObj);
  }

  ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
  sCallbackEnv->CallVoidMethod(
          mCallbacksObj, android_bluetooth_A2dpNativeCallback.onCodecConfigChanged, addr.get(),
          codecConfigObj, local_capabilities_array, selectable_capabilities_array);
}

static bool bta2dp_mandatory_codec_preferred_callback(const RawAddress& bd_addr) {
  log::info("{}", bd_addr);

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
    return false;
  }

  ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
  return sCallbackEnv->CallBooleanMethod(
          mCallbacksObj, android_bluetooth_A2dpNativeCallback.isMandatoryCodecPreferred,
          addr.get());
}

static void bta2dp_audio_delay_reported_callback(const RawAddress& bd_addr, int delay) {
  log::info("bd_addr={} delay={}", bd_addr, delay);

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
    return;
  }

  ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
  sCallbackEnv->CallVoidMethod(mCallbacksObj,
                               android_bluetooth_A2dpNativeCallback.onAudioDelayReported,
                               addr.get(), (jint)delay);
}

static btav_source_callbacks_t sBluetoothA2dpCallbacks = {
        sizeof(sBluetoothA2dpCallbacks),
        bta2dp_connection_state_callback,
        bta2dp_audio_state_callback,
        bta2dp_audio_config_callback,
        bta2dp_mandatory_codec_preferred_callback,
        bta2dp_audio_delay_reported_callback,
};

static std::vector<btav_a2dp_codec_config_t> prepareCodecPreferences(
        JNIEnv* env, jobject /* object */, jobjectArray codecConfigArray) {
  std::vector<btav_a2dp_codec_config_t> codec_preferences;

  int numConfigs = env->GetArrayLength(codecConfigArray);
  for (int i = 0; i < numConfigs; i++) {
    jobject jcodecConfig = env->GetObjectArrayElement(codecConfigArray, i);
    if (jcodecConfig == nullptr) {
      continue;
    }
    if (!env->IsInstanceOf(jcodecConfig, android_bluetooth_BluetoothCodecConfig.clazz)) {
      log::error("Invalid BluetoothCodecConfig instance");
      continue;
    }

    jint codecType =
            env->CallIntMethod(jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecType);
    jint codecPriority = env->CallIntMethod(
            jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecPriority);
    jint sampleRate =
            env->CallIntMethod(jcodecConfig, android_bluetooth_BluetoothCodecConfig.getSampleRate);
    jint bitsPerSample = env->CallIntMethod(
            jcodecConfig, android_bluetooth_BluetoothCodecConfig.getBitsPerSample);
    jint channelMode =
            env->CallIntMethod(jcodecConfig, android_bluetooth_BluetoothCodecConfig.getChannelMode);
    jlong codecSpecific1 = env->CallLongMethod(
            jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecSpecific1);
    jlong codecSpecific2 = env->CallLongMethod(
            jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecSpecific2);
    jlong codecSpecific3 = env->CallLongMethod(
            jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecSpecific3);
    jlong codecSpecific4 = env->CallLongMethod(
            jcodecConfig, android_bluetooth_BluetoothCodecConfig.getCodecSpecific4);

    btav_a2dp_codec_config_t codec_config = {
            .codec_type = static_cast<btav_a2dp_codec_index_t>(codecType),
            .codec_priority = static_cast<btav_a2dp_codec_priority_t>(codecPriority),
            .sample_rate = static_cast<btav_a2dp_codec_sample_rate_t>(sampleRate),
            .bits_per_sample = static_cast<btav_a2dp_codec_bits_per_sample_t>(bitsPerSample),
            .channel_mode = static_cast<btav_a2dp_codec_channel_mode_t>(channelMode),
            .codec_specific_1 = codecSpecific1,
            .codec_specific_2 = codecSpecific2,
            .codec_specific_3 = codecSpecific3,
            .codec_specific_4 = codecSpecific4};

    codec_preferences.push_back(codec_config);
  }
  return codec_preferences;
}

static void initNative(JNIEnv* env, jobject object, jint maxConnectedAudioDevices,
                       jobjectArray codecConfigArray, jobjectArray codecOffloadingArray) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (mCallbacksObj != nullptr) {
    log::warn("Cleaning up A2DP callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  if ((mCallbacksObj = env->NewGlobalRef(env->GetObjectField(
               object, android_bluetooth_A2dpNativeInterface.mNativeCallback))) == nullptr) {
    log::fatal("Failed to allocate Global Ref for A2DP Callbacks");
  }

  android_bluetooth_BluetoothCodecConfig.clazz =
          (jclass)env->NewGlobalRef(env->FindClass("android/bluetooth/BluetoothCodecConfig"));
  if (android_bluetooth_BluetoothCodecConfig.clazz == nullptr) {
    log::error("Failed to allocate Global Ref for BluetoothCodecConfig class");
    return;
  }

  android_bluetooth_BluetoothCodecType.clazz =
          (jclass)env->NewGlobalRef(env->FindClass("android/bluetooth/BluetoothCodecType"));
  if (android_bluetooth_BluetoothCodecType.clazz == nullptr) {
    log::error("Failed to allocate Global Ref for BluetoothCodecType class");
    return;
  }

  std::vector<btav_a2dp_codec_config_t> codec_priorities =
          prepareCodecPreferences(env, object, codecConfigArray);

  std::vector<btav_a2dp_codec_config_t> codec_offloading =
          prepareCodecPreferences(env, object, codecOffloadingArray);

  BtStatus status = btif_av_source_init(&sBluetoothA2dpCallbacks, maxConnectedAudioDevices,
                                        codec_priorities, codec_offloading, &supported_codecs);
  if (!status) {
    log::error("Failed to initialize Bluetooth A2DP, status: {}", status);
    return;
  }
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  btif_av_source_cleanup();

  env->DeleteGlobalRef(android_bluetooth_BluetoothCodecConfig.clazz);
  android_bluetooth_BluetoothCodecConfig.clazz = nullptr;

  if (mCallbacksObj != nullptr) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }
}

static jobjectArray getSupportedCodecTypesNative(JNIEnv* env) {
  jobjectArray result = env->NewObjectArray(supported_codecs.size(),
                                            android_bluetooth_BluetoothCodecType.clazz, nullptr);

  if (result == nullptr) {
    log::error("Failed to allocate result array of BluetoothCodecType");
    return nullptr;
  }

  for (size_t index = 0; index < supported_codecs.size(); index++) {
    uint64_t codec_id = static_cast<uint64_t>(supported_codecs[index].codec_id);
    jobject codec_type = env->NewObject(android_bluetooth_BluetoothCodecType.clazz,
                                        android_bluetooth_BluetoothCodecType.constructor,
                                        (jint)supported_codecs[index].codec_capabilities.codec_type,
                                        (jlong)codec_id,
                                        env->NewStringUTF(supported_codecs[index].name.c_str()));
    env->SetObjectArrayElement(result, index, codec_type);
  }

  return result;
}

static jboolean connectA2dpNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  RawAddress bd_addr = addressFromJByteArray(env, address);

  log::info("{}", bd_addr);

  BtStatus status = btif_av_source_connect(bd_addr);
  if (!status) {
    log::error("Failed A2DP connection, status: {}", status);
  }
  return status ? JNI_TRUE : JNI_FALSE;
}

static jboolean disconnectA2dpNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  RawAddress bd_addr = addressFromJByteArray(env, address);

  log::info("{}", bd_addr);
  BtStatus status = btif_av_source_disconnect(bd_addr);
  if (!status) {
    log::error("Failed A2DP disconnection, status: {}", status);
  }
  return status ? JNI_TRUE : JNI_FALSE;
}

static jboolean setSilenceDeviceNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                       jboolean silence) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  RawAddress bd_addr = addressFromJByteArray(env, address);

  log::info("{} silence={}", bd_addr, silence);

  BtStatus status = btif_av_source_set_silence_device(bd_addr, silence);
  if (!status) {
    log::error("Failed A2DP set_silence_device, status: {}", status);
  }
  return status ? JNI_TRUE : JNI_FALSE;
}

static jboolean setActiveDeviceNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  RawAddress bd_addr = addressFromJByteArray(env, address);

  log::info("{}", bd_addr);

  BtStatus status = btif_av_source_set_active_device(bd_addr);
  if (!status) {
    log::error("Failed A2DP set_active_device, status: {}", status);
  }
  return status ? JNI_TRUE : JNI_FALSE;
}

static jboolean setCodecConfigPreferenceNative(JNIEnv* env, jobject object, jbyteArray address,
                                               jobjectArray codecConfigArray) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  RawAddress bd_addr = addressFromJByteArray(env, address);
  std::vector<btav_a2dp_codec_config_t> codec_preferences =
          prepareCodecPreferences(env, object, codecConfigArray);

  log::info("{}: {}", bd_addr, btav_a2dp_codec_config_t::PrintCodecs(codec_preferences));

  BtStatus status = btif_av_source_set_codec_config_preference(bd_addr, codec_preferences);
  if (!status) {
    log::error("Failed codec configuration, status: {}", status);
  }
  return status ? JNI_TRUE : JNI_FALSE;
}

int register_com_android_bluetooth_a2dp(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative",
           "(I[Landroid/bluetooth/BluetoothCodecConfig;"
           "[Landroid/bluetooth/BluetoothCodecConfig;)V",
           (void*)initNative},
          {"cleanupNative", "()V", (void*)cleanupNative},
          {"getSupportedCodecTypesNative", "()[Landroid/bluetooth/BluetoothCodecType;",
           (void*)getSupportedCodecTypesNative},
          {"connectA2dpNative", "([B)Z", (void*)connectA2dpNative},
          {"disconnectA2dpNative", "([B)Z", (void*)disconnectA2dpNative},
          {"setSilenceDeviceNative", "([BZ)Z", (void*)setSilenceDeviceNative},
          {"setActiveDeviceNative", "([B)Z", (void*)setActiveDeviceNative},
          {"setCodecConfigPreferenceNative", "([B[Landroid/bluetooth/BluetoothCodecConfig;)Z",
           (void*)setCodecConfigPreferenceNative},
  };
  const int result =
          REGISTER_NATIVE_METHODS(env, "com/android/bluetooth/a2dp/A2dpNativeInterface", methods);
  if (result != 0) {
    return result;
  }

  const JNIJavaMethod javaMethods[] = {
          {"onConnectionStateChanged", "([BII)V",
           &android_bluetooth_A2dpNativeCallback.onConnectionStateChanged},
          {"onAudioStateChanged", "([BI)V",
           &android_bluetooth_A2dpNativeCallback.onAudioStateChanged},
          {"onCodecConfigChanged",
           "([BLandroid/bluetooth/BluetoothCodecConfig;"
           "[Landroid/bluetooth/BluetoothCodecConfig;"
           "[Landroid/bluetooth/BluetoothCodecConfig;)V",
           &android_bluetooth_A2dpNativeCallback.onCodecConfigChanged},
          {"onAudioDelayReported", "([BI)V",
           &android_bluetooth_A2dpNativeCallback.onAudioDelayReported},
          {"isMandatoryCodecPreferred", "([B)Z",
           &android_bluetooth_A2dpNativeCallback.isMandatoryCodecPreferred},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/a2dp/A2dpNativeCallback", javaMethods);

  jclass jniA2dpNativeInterfaceClass =
          env->FindClass("com/android/bluetooth/a2dp/A2dpNativeInterface");
  android_bluetooth_A2dpNativeInterface.mNativeCallback =
          env->GetFieldID(jniA2dpNativeInterfaceClass, "nativeCallback",
                          "Lcom/android/bluetooth/profile/NativeCallback;");
  env->DeleteLocalRef(jniA2dpNativeInterfaceClass);

  const JNIJavaMethod codecConfigCallbacksMethods[] = {
          {"<init>", "(Landroid/bluetooth/BluetoothCodecType;IIIIJJJJ)V",
           &android_bluetooth_BluetoothCodecConfig.constructor},
          {"getCodecType", "()I", &android_bluetooth_BluetoothCodecConfig.getCodecType},
          {"getExtendedCodecType", "()Landroid/bluetooth/BluetoothCodecType;",
           &android_bluetooth_BluetoothCodecConfig.getExtendedCodecType},
          {"getCodecPriority", "()I", &android_bluetooth_BluetoothCodecConfig.getCodecPriority},
          {"getSampleRate", "()I", &android_bluetooth_BluetoothCodecConfig.getSampleRate},
          {"getBitsPerSample", "()I", &android_bluetooth_BluetoothCodecConfig.getBitsPerSample},
          {"getChannelMode", "()I", &android_bluetooth_BluetoothCodecConfig.getChannelMode},
          {"getCodecSpecific1", "()J", &android_bluetooth_BluetoothCodecConfig.getCodecSpecific1},
          {"getCodecSpecific2", "()J", &android_bluetooth_BluetoothCodecConfig.getCodecSpecific2},
          {"getCodecSpecific3", "()J", &android_bluetooth_BluetoothCodecConfig.getCodecSpecific3},
          {"getCodecSpecific4", "()J", &android_bluetooth_BluetoothCodecConfig.getCodecSpecific4},
  };
  GET_JAVA_METHODS(env, "android/bluetooth/BluetoothCodecConfig", codecConfigCallbacksMethods);

  const JNIJavaMethod bluetoothCodecTypeMethods[] = {
          {"<init>", "(IJLjava/lang/String;)V", &android_bluetooth_BluetoothCodecType.constructor},
          {"getCodecId", "()J", &android_bluetooth_BluetoothCodecType.getCodecId},
  };
  GET_JAVA_METHODS(env, "android/bluetooth/BluetoothCodecType", bluetoothCodecTypeMethods);

  return 0;
}
}  // namespace android
