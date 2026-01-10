/*
 * Copyright 2026 The Android Open Source Project
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

#define LOG_TAG "BluetoothLeAudioBroadcasterServiceJni"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <string>
#include <type_traits>
#include <vector>

#include "bluetooth/log.h"
#include "bluetooth/types/address.h"
#include "com_android_bluetooth.h"
#include "com_android_bluetooth_le_audio_utils.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_le_audio.h"
#include "jni.h"
#include "nativehelper/JNIHelp.h"
#include "nativehelper/scoped_local_ref.h"

using bluetooth::le_audio::BroadcastState;
using bluetooth::le_audio::LeAudioBroadcasterCallbacks;
using bluetooth::le_audio::LeAudioBroadcasterInterface;

namespace android {

/* Le Audio Broadcaster */
static jmethodID method_onBroadcastCreated;
static jmethodID method_onBroadcastDestroyed;
static jmethodID method_onBroadcastStateChanged;
static jmethodID method_onBroadcastMetadataChanged;
static jmethodID method_onBroadcastAudioSessionCreated;

static LeAudioBroadcasterInterface* sLeAudioBroadcasterInterface = nullptr;
static std::shared_timed_mutex sBroadcasterInterfaceMutex;

static jobject sBroadcasterCallbacksObj = nullptr;
static std::shared_timed_mutex sBroadcasterCallbacksMutex;

class LeAudioBroadcasterCallbacksImpl : public LeAudioBroadcasterCallbacks {
public:
  ~LeAudioBroadcasterCallbacksImpl() = default;

  void OnBroadcastCreated(uint32_t broadcast_id, bool success) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterCallbacksMutex);
    CallbackEnv sCallbackEnv(__func__);

    if (!sCallbackEnv.valid() || sBroadcasterCallbacksObj == nullptr) {
      return;
    }
    sCallbackEnv->CallVoidMethod(sBroadcasterCallbacksObj, method_onBroadcastCreated,
                                 (jint)broadcast_id, success ? JNI_TRUE : JNI_FALSE);
  }

  void OnBroadcastDestroyed(uint32_t broadcast_id) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterCallbacksMutex);
    CallbackEnv sCallbackEnv(__func__);

    if (!sCallbackEnv.valid() || sBroadcasterCallbacksObj == nullptr) {
      return;
    }
    sCallbackEnv->CallVoidMethod(sBroadcasterCallbacksObj, method_onBroadcastDestroyed,
                                 (jint)broadcast_id);
  }

  void OnBroadcastStateChanged(uint32_t broadcast_id, BroadcastState state) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterCallbacksMutex);
    CallbackEnv sCallbackEnv(__func__);

    if (!sCallbackEnv.valid() || sBroadcasterCallbacksObj == nullptr) {
      return;
    }
    sCallbackEnv->CallVoidMethod(
            sBroadcasterCallbacksObj, method_onBroadcastStateChanged, (jint)broadcast_id,
            (jint) static_cast<std::underlying_type<BroadcastState>::type>(state));
  }

  void OnBroadcastMetadataChanged(
          uint32_t broadcast_id,
          const bluetooth::le_audio::BroadcastMetadata& broadcast_metadata) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterCallbacksMutex);
    CallbackEnv sCallbackEnv(__func__);

    ScopedLocalRef<jobject> metadata_obj(
            sCallbackEnv.get(),
            prepareBluetoothLeBroadcastMetadataObject(sCallbackEnv.get(), broadcast_metadata));

    if (!sCallbackEnv.valid() || sBroadcasterCallbacksObj == nullptr) {
      return;
    }
    sCallbackEnv->CallVoidMethod(sBroadcasterCallbacksObj, method_onBroadcastMetadataChanged,
                                 (jint)broadcast_id, metadata_obj.get());
  }

  void OnBroadcastAudioSessionCreated(bool success) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterCallbacksMutex);
    CallbackEnv sCallbackEnv(__func__);

    if (!sCallbackEnv.valid() || sBroadcasterCallbacksObj == nullptr) {
      return;
    }
    sCallbackEnv->CallVoidMethod(sBroadcasterCallbacksObj, method_onBroadcastAudioSessionCreated,
                                 success ? JNI_TRUE : JNI_FALSE);
  }
};

static LeAudioBroadcasterCallbacksImpl sLeAudioBroadcasterCallbacks;

static void BroadcasterInitNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(sBroadcasterInterfaceMutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(sBroadcasterCallbacksMutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  UtilsInit(env);

  if (sBroadcasterCallbacksObj != nullptr) {
    log::info("Cleaning up LeAudio Broadcaster callback object");
    env->DeleteGlobalRef(sBroadcasterCallbacksObj);
    sBroadcasterCallbacksObj = nullptr;
  }

  if ((sBroadcasterCallbacksObj = env->NewGlobalRef(object)) == nullptr) {
    log::fatal("Failed to allocate Global Ref for LeAudio Broadcaster Callbacks");
  }

  sLeAudioBroadcasterInterface = (LeAudioBroadcasterInterface*)btInf->get_profile_interface(
          BT_PROFILE_LE_AUDIO_BROADCASTER_ID);
  if (sLeAudioBroadcasterInterface == nullptr) {
    log::error("Failed to get Bluetooth LeAudio Broadcaster Interface");
    return;
  }

  sLeAudioBroadcasterInterface->Initialize(&sLeAudioBroadcasterCallbacks);
}

static void BroadcasterStopNative(JNIEnv* /* env */, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(sBroadcasterInterfaceMutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sLeAudioBroadcasterInterface != nullptr) {
    sLeAudioBroadcasterInterface->Stop();
  }
}

static void BroadcasterCleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(sBroadcasterInterfaceMutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(sBroadcasterCallbacksMutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  UtilsCleanup(env);

  if (sLeAudioBroadcasterInterface != nullptr) {
    sLeAudioBroadcasterInterface->Cleanup();
    sLeAudioBroadcasterInterface = nullptr;
  }

  if (sBroadcasterCallbacksObj != nullptr) {
    env->DeleteGlobalRef(sBroadcasterCallbacksObj);
    sBroadcasterCallbacksObj = nullptr;
  }
}

static std::vector<std::vector<uint8_t>> convertToDataVectors(JNIEnv* env, jobjectArray dataArray) {
  jsize arraySize = env->GetArrayLength(dataArray);
  std::vector<std::vector<uint8_t>> res(arraySize);

  for (int i = 0; i < arraySize; ++i) {
    jbyteArray rowData = (jbyteArray)env->GetObjectArrayElement(dataArray, i);
    jsize dataSize = env->GetArrayLength(rowData);
    std::vector<uint8_t>& rowVector = res[i];
    rowVector.resize(dataSize);
    env->GetByteArrayRegion(rowData, 0, dataSize, reinterpret_cast<jbyte*>(rowVector.data()));
    env->DeleteLocalRef(rowData);
  }
  return res;
}

static void CreateBroadcastNative(JNIEnv* env, jobject /* object */, jboolean isPublic,
                                  jstring broadcastName, jbyteArray broadcast_code,
                                  jbyteArray publicMetadata, jintArray qualityArray,
                                  jobjectArray metadataArray) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) {
    return;
  }

  std::array<uint8_t, 16> code_array{0};
  if (broadcast_code) {
    jsize size = env->GetArrayLength(broadcast_code);
    if (size > 16) {
      log::error("broadcast code to long");
      return;
    }

    // Padding with zeros on MSB positions if code is shorter than 16 octets
    env->GetByteArrayRegion(broadcast_code, 0, size, (jbyte*)code_array.data());
  }

  const char* broadcast_name = nullptr;
  if (broadcastName) {
    broadcast_name = env->GetStringUTFChars(broadcastName, nullptr);
  }

  jbyte* public_meta = nullptr;
  if (publicMetadata) {
    public_meta = env->GetByteArrayElements(publicMetadata, nullptr);
  }

  jint* quality_array = nullptr;
  if (qualityArray) {
    quality_array = env->GetIntArrayElements(qualityArray, nullptr);
  }

  sLeAudioBroadcasterInterface->CreateBroadcast(
          isPublic, broadcast_name ? broadcast_name : "",
          broadcast_code ? std::optional<std::array<uint8_t, 16>>(code_array) : std::nullopt,
          public_meta ? std::vector<uint8_t>(public_meta,
                                             public_meta + env->GetArrayLength(publicMetadata))
                      : std::vector<uint8_t>(),
          quality_array ? std::vector<uint8_t>(quality_array,
                                               quality_array + env->GetArrayLength(qualityArray))
                        : std::vector<uint8_t>(),
          convertToDataVectors(env, metadataArray));

  if (broadcast_name) {
    env->ReleaseStringUTFChars(broadcastName, broadcast_name);
  }
  if (public_meta) {
    env->ReleaseByteArrayElements(publicMetadata, public_meta, 0);
  }
  if (quality_array) {
    env->ReleaseIntArrayElements(qualityArray, quality_array, 0);
  }
}

static void UpdateMetadataNative(JNIEnv* env, jobject /* object */, jint broadcast_id,
                                 jstring broadcastName, jbyteArray publicMetadata,
                                 jobjectArray metadataArray) {
  const char* broadcast_name = nullptr;
  if (broadcastName) {
    broadcast_name = env->GetStringUTFChars(broadcastName, nullptr);
  }

  jbyte* public_meta = nullptr;
  if (publicMetadata) {
    public_meta = env->GetByteArrayElements(publicMetadata, nullptr);
  }

  sLeAudioBroadcasterInterface->UpdateMetadata(
          broadcast_id, broadcast_name ? broadcast_name : "",
          public_meta ? std::vector<uint8_t>(public_meta,
                                             public_meta + env->GetArrayLength(publicMetadata))
                      : std::vector<uint8_t>(),
          convertToDataVectors(env, metadataArray));

  if (broadcast_name) {
    env->ReleaseStringUTFChars(broadcastName, broadcast_name);
  }
  if (public_meta) {
    env->ReleaseByteArrayElements(publicMetadata, public_meta, 0);
  }
}

static void StartBroadcastNative(JNIEnv* /* env */, jobject /* object */, jint broadcast_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) {
    return;
  }
  sLeAudioBroadcasterInterface->StartBroadcast(broadcast_id);
}

static void StopBroadcastNative(JNIEnv* /* env */, jobject /* object */, jint broadcast_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) {
    return;
  }
  sLeAudioBroadcasterInterface->StopBroadcast(broadcast_id);
}

static void PauseBroadcastNative(JNIEnv* /* env */, jobject /* object */, jint broadcast_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) {
    return;
  }
  sLeAudioBroadcasterInterface->PauseBroadcast(broadcast_id);
}

static void DestroyBroadcastNative(JNIEnv* /* env */, jobject /* object */, jint broadcast_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) {
    return;
  }
  sLeAudioBroadcasterInterface->DestroyBroadcast(broadcast_id);
}

static void getBroadcastMetadataNative(JNIEnv* /* env */, jobject /* object */, jint broadcast_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) {
    return;
  }
  sLeAudioBroadcasterInterface->GetBroadcastMetadata(broadcast_id);
}

static void setBigChannelMapClassificationNative(JNIEnv* env, jobject /* object */, jint action,
                                                 jbyteArray sink_addr, jint broadcast_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) {
    log::error("sLeAudioBroadcasterInterface is null");
    return;
  }

  RawAddress bd_addr = addressFromJByteArray(env, sink_addr);
  sLeAudioBroadcasterInterface->SetBigChannelMapClassification(action, bd_addr, broadcast_id);
}

int register_com_android_bluetooth_le_audio_broadcaster(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative", "()V", (void*)BroadcasterInitNative},
          {"stopNative", "()V", (void*)BroadcasterStopNative},
          {"cleanupNative", "()V", (void*)BroadcasterCleanupNative},
          {"createBroadcastNative", "(ZLjava/lang/String;[B[B[I[[B)V",
           (void*)CreateBroadcastNative},
          {"updateMetadataNative", "(ILjava/lang/String;[B[[B)V", (void*)UpdateMetadataNative},
          {"startBroadcastNative", "(I)V", (void*)StartBroadcastNative},
          {"stopBroadcastNative", "(I)V", (void*)StopBroadcastNative},
          {"pauseBroadcastNative", "(I)V", (void*)PauseBroadcastNative},
          {"destroyBroadcastNative", "(I)V", (void*)DestroyBroadcastNative},
          {"getBroadcastMetadataNative", "(I)V", (void*)getBroadcastMetadataNative},
          {"setBigChannelMapClassificationNative", "(I[BI)V",
           (void*)setBigChannelMapClassificationNative},
  };

  const int result = REGISTER_NATIVE_METHODS(
          env, "com/android/bluetooth/le_audio/LeAudioBroadcasterNativeInterface", methods);
  if (result != 0) {
    return result;
  }

  const JNIJavaMethod javaMethods[] = {
          {"onBroadcastCreated", "(IZ)V", &method_onBroadcastCreated},
          {"onBroadcastDestroyed", "(I)V", &method_onBroadcastDestroyed},
          {"onBroadcastStateChanged", "(II)V", &method_onBroadcastStateChanged},
          {"onBroadcastMetadataChanged", "(ILandroid/bluetooth/BluetoothLeBroadcastMetadata;)V",
           &method_onBroadcastMetadataChanged},
          {"onBroadcastAudioSessionCreated", "(Z)V", &method_onBroadcastAudioSessionCreated},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/le_audio/LeAudioBroadcasterNativeInterface",
                   javaMethods);

  return 0;
}
}  // namespace android
