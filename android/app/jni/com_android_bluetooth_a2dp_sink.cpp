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

#define LOG_TAG "BluetoothA2dpSinkServiceJni"

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

#include "bt_status.h"
#include "btif/include/btif_av.h"
#include "btif/include/btif_util.h"
#include "com_android_bluetooth.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_av.h"

namespace android {
static jmethodID method_onConnectionStateChanged;
static jmethodID method_onAudioStateChanged;
static jmethodID method_onAudioConfigChanged;

static jobject mCallbacksObj = NULL;
static jfieldID sCallbacksField;
static std::shared_timed_mutex callbacks_mutex;

static void a2dp_sink_connection_state_callback(const RawAddress& bd_addr,
                                                btav_connection_state_t state,
                                                const btav_error_t& /* error */) {
  log::info("addr={}, state={}", bd_addr, dump_av_conn_state(state));
  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  if (!mCallbacksObj) {
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) {
    return;
  }

  ScopedLocalRef<jbyteArray> jaddr = addressToJByteArray(sCallbackEnv, bd_addr);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectionStateChanged, jaddr.get(),
                               (jint)state);
}

static void a2dp_sink_audio_state_callback(const RawAddress& bd_addr, btav_audio_state_t state) {
  log::info("addr={}, state={}", bd_addr, dump_av_audio_state(state));
  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  if (!mCallbacksObj) {
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) {
    return;
  }

  ScopedLocalRef<jbyteArray> jaddr = addressToJByteArray(sCallbackEnv, bd_addr);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAudioStateChanged, jaddr.get(),
                               (jint)state);
}

static void a2dp_sink_audio_config_callback(const RawAddress& bd_addr, uint32_t sample_rate,
                                            uint8_t channel_count) {
  log::info("addr={}, sample_rate={}, channels={}", bd_addr, sample_rate, channel_count);
  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  if (!mCallbacksObj) {
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) {
    return;
  }

  ScopedLocalRef<jbyteArray> jaddr = addressToJByteArray(sCallbackEnv, bd_addr);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAudioConfigChanged, jaddr.get(),
                               (jint)sample_rate, (jint)channel_count);
}

static btav_sink_callbacks_t sBluetoothA2dpCallbacks = {
        sizeof(sBluetoothA2dpCallbacks),
        a2dp_sink_connection_state_callback,
        a2dp_sink_audio_state_callback,
        a2dp_sink_audio_config_callback,
};

static void initNative(JNIEnv* env, jobject object, jint maxConnectedAudioDevices) {
  std::unique_lock<std::shared_timed_mutex> lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == NULL) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (mCallbacksObj != NULL) {
    log::warn("Cleaning up A2DP callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = NULL;
  }

  BtStatus status = btif_av_sink_init(&sBluetoothA2dpCallbacks, maxConnectedAudioDevices);
  if (!status) {
    log::error("Failed to initialize Bluetooth A2DP Sink, status: {}", status);
    return;
  }

  if ((mCallbacksObj = env->NewGlobalRef(env->GetObjectField(object, sCallbacksField))) ==
      nullptr) {
    log::fatal("Failed to allocate Global Ref for A2DP Sink Callbacks");
  }
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  const bt_interface_t* btInf = getBluetoothInterface();

  if (btInf == NULL) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  btif_av_sink_cleanup();

  if (mCallbacksObj != NULL) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = NULL;
  }
}

static jboolean connectA2dpNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  RawAddress bd_addr = addressFromJByteArray(env, address);

  log::info("{}", bd_addr);
  BtStatus status = btif_av_sink_connect(bd_addr);
  if (!status) {
    log::error("Failed HF connection, status: {}", status);
  }

  return status ? JNI_TRUE : JNI_FALSE;
}

static jboolean disconnectA2dpNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  RawAddress bd_addr = addressFromJByteArray(env, address);

  log::info("{}", bd_addr);
  BtStatus status = btif_av_sink_disconnect(bd_addr);
  if (!status) {
    log::error("Failed HF disconnection, status: {}", status);
  }

  return status ? JNI_TRUE : JNI_FALSE;
}

static void informAudioFocusStateNative(JNIEnv* /* env */, jobject /* object */, jint focus_state) {
  btif_av_sink_set_audio_focus_state(static_cast<uint8_t>(focus_state));
}

static void informAudioTrackGainNative(JNIEnv* /* env */, jobject /* object */, jfloat gain) {
  btif_av_sink_set_audio_track_gain(static_cast<float>(gain));
}

static jboolean setActiveDeviceNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  RawAddress bd_addr = addressFromJByteArray(env, address);

  log::info("{}", bd_addr);
  BtStatus status = btif_av_sink_set_active_device(bd_addr);
  if (!status) {
    log::error("Failed sending passthru command, status: {}", status);
  }

  return status ? JNI_TRUE : JNI_FALSE;
}

// JNI functions defined in A2dpSinkNativeInterface
int register_com_android_bluetooth_a2dp_sink(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative", "(I)V", reinterpret_cast<void*>(initNative)},
          {"cleanupNative", "()V", reinterpret_cast<void*>(cleanupNative)},
          {"connectA2dpNative", "([B)Z", reinterpret_cast<void*>(connectA2dpNative)},
          {"disconnectA2dpNative", "([B)Z", reinterpret_cast<void*>(disconnectA2dpNative)},
          {"informAudioFocusStateNative", "(I)V",
           reinterpret_cast<void*>(informAudioFocusStateNative)},
          {"informAudioTrackGainNative", "(F)V",
           reinterpret_cast<void*>(informAudioTrackGainNative)},
          {"setActiveDeviceNative", "([B)Z", reinterpret_cast<void*>(setActiveDeviceNative)},
  };
  const char* jniNativeInterfaceClass = "com/android/bluetooth/a2dpsink/A2dpSinkNativeInterface";
  const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
  if (result != 0) {
    return result;
  }

  sCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

  // Client callback functions defined in A2dpSinkNativeCallback
  const JNIJavaMethod javaMethods[] = {
          {"onConnectionStateChanged", "([BI)V", &method_onConnectionStateChanged},
          {"onAudioStateChanged", "([BI)V", &method_onAudioStateChanged},
          {"onAudioConfigChanged", "([BII)V", &method_onAudioConfigChanged},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/a2dpsink/A2dpSinkNativeCallback", javaMethods);

  return 0;
}
}  // namespace android
