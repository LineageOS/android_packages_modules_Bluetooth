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

#define LOG_TAG "BluetoothVcpRendererJni"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_local_ref.h>

#include <cstdint>
#include <mutex>
#include <shared_mutex>

#include "com_android_bluetooth.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_vcp_renderer.h"

namespace android {

static jmethodID method_onInitialized;
static jmethodID method_onConnectionStateChanged;
static jmethodID method_onVolumeStateChangeRequest;

static bluetooth::vcp::VolumeRendererInterface* sVcpRendererInterface = nullptr;
static std::shared_timed_mutex interface_mutex;

static jobject mCallbacksObj = nullptr;
static std::shared_timed_mutex callbacks_mutex;

static jfieldID sCallbacksField;

class VcpRendererCallbacksImpl : public bluetooth::vcp::VolumeRendererCallbacks {
public:
  ~VcpRendererCallbacksImpl() = default;

  void OnInitialized(void) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onInitialized);
  }

  void OnGattConnectionStateChanged(const RawAddress& address,
                                    bluetooth::vcp::GattConnectionState state) override {
    log::info("addr: {}, state: {}", address, static_cast<int>(state));

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectionStateChanged, addr.get(),
                                 (jint)state);
  }

  void OnVolumeStateChangeRequest(uint8_t volume, bluetooth::vcp::MuteState mute_state) override {
    log::info("volume: {}, mute_state: {}", volume, static_cast<int>(mute_state));

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onVolumeStateChangeRequest, (jint)volume,
                                 (jint)mute_state);
  }
};

static VcpRendererCallbacksImpl sVcpRendererCallbacks;

static void initNative(JNIEnv* env, jobject object, jobject config) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sVcpRendererInterface != nullptr) {
    log::info("Cleaning up VcpRenderer Interface before initializing...");
    sVcpRendererInterface->Cleanup();
    sVcpRendererInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    log::info("Cleaning up VcpRenderer callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  if ((mCallbacksObj = env->NewGlobalRef(env->GetObjectField(object, sCallbacksField))) ==
      nullptr) {
    log::fatal("Failed to allocate Global Ref for VcpRenderer Callbacks");
  }

  sVcpRendererInterface = const_cast<bluetooth::vcp::VolumeRendererInterface*>(
          reinterpret_cast<const bluetooth::vcp::VolumeRendererInterface*>(
                  btInf->get_profile_interface(BT_PROFILE_VCP_RENDERER_ID)));

  if (sVcpRendererInterface == nullptr) {
    log::fatal("Failed to get Bluetooth VcpRenderer Interface");
    return;
  }

  if (config == nullptr) {
    log::fatal("Config object is null");
    return;
  }

  jclass configClass = env->GetObjectClass(config);
  jfieldID initialVolumeField = env->GetFieldID(configClass, "initialVolume", "I");
  jfieldID initialMuteStateField =
          env->GetFieldID(configClass, "initialMuteState", "Lcom/android/bluetooth/vcp/MuteState;");
  jfieldID initialVolumeSettingPersistedField =
          env->GetFieldID(configClass, "initialVolumeSettingPersisted",
                          "Lcom/android/bluetooth/vcp/VolumeSettingPersisted;");
  jfieldID volumeStepSizeField = env->GetFieldID(configClass, "volumeStepSize", "I");

  if (!initialVolumeField || !initialMuteStateField || !initialVolumeSettingPersistedField ||
      !volumeStepSizeField) {
    log::fatal("Failed to get config fields");
    return;
  }

  jobject muteStateObj = env->GetObjectField(config, initialMuteStateField);
  if (muteStateObj == nullptr) {
    log::fatal("initialMuteState is null");
    return;
  }
  jclass muteStateClass = env->GetObjectClass(muteStateObj);
  jmethodID muteStateGetValue = env->GetMethodID(muteStateClass, "getValue", "()I");
  jint initialMuteState = env->CallIntMethod(muteStateObj, muteStateGetValue);

  jobject volumeSettingPersistedObj =
          env->GetObjectField(config, initialVolumeSettingPersistedField);
  if (volumeSettingPersistedObj == nullptr) {
    log::fatal("initialVolumeSettingPersisted is null");
    return;
  }
  jclass volumeSettingPersistedClass = env->GetObjectClass(volumeSettingPersistedObj);
  jmethodID volumeSettingPersistedGetValue =
          env->GetMethodID(volumeSettingPersistedClass, "getValue", "()I");
  jint initialVolumeSettingPersisted =
          env->CallIntMethod(volumeSettingPersistedObj, volumeSettingPersistedGetValue);

  bluetooth::vcp::VolumeRendererConfig nativeConfig;
  nativeConfig.initial_volume = env->GetIntField(config, initialVolumeField);
  nativeConfig.initial_mute_state = static_cast<bluetooth::vcp::MuteState>(initialMuteState);
  nativeConfig.initial_volume_setting_persisted =
          static_cast<bluetooth::vcp::VolumeSettingPersisted>(initialVolumeSettingPersisted);
  nativeConfig.volume_step_size = env->GetIntField(config, volumeStepSizeField);

  sVcpRendererInterface->Initialize(&sVcpRendererCallbacks, nativeConfig);
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sVcpRendererInterface != nullptr) {
    sVcpRendererInterface->Cleanup();
    sVcpRendererInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }
}

static void updateVolumeStateNative(JNIEnv* /* env */, jobject /* object */, jint volume,
                                    jint mute_state) {
  log::info("volume: {}, mute_state: {}", volume, mute_state);
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVcpRendererInterface) {
    log::error("sVcpRendererInterface is null");
    return;
  }
  sVcpRendererInterface->UpdateVolumeState(volume,
                                           static_cast<bluetooth::vcp::MuteState>(mute_state));
}

static void updateVolumeFlagsNative(JNIEnv* /* env */, jobject /* object */,
                                    jint volume_setting_persisted) {
  log::info("volume_setting_persisted: {}", volume_setting_persisted);
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVcpRendererInterface) {
    log::error("sVcpRendererInterface is null");
    return;
  }
  bluetooth::vcp::VolumeFlags flags;
  flags.bits.volume_setting_persisted =
          static_cast<bluetooth::vcp::VolumeSettingPersisted>(volume_setting_persisted);
  sVcpRendererInterface->UpdateVolumeFlags(flags);
}

int register_com_android_bluetooth_vcp_renderer(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative", "(Lcom/android/bluetooth/vcp/VcpRendererConfig;)V",
           reinterpret_cast<void*>(initNative)},
          {"cleanupNative", "()V", reinterpret_cast<void*>(cleanupNative)},
          {"updateVolumeStateNative", "(II)V", reinterpret_cast<void*>(updateVolumeStateNative)},
          {"updateVolumeFlagsNative", "(I)V", reinterpret_cast<void*>(updateVolumeFlagsNative)},
  };
  const char* jniNativeInterfaceClass = "com/android/bluetooth/vcp/VcpRendererNativeInterface";
  const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
  if (result != 0) {
    return result;
  }

  sCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

  const JNIJavaMethod javaMethods[] = {
          {"onInitialized", "()V", &method_onInitialized},
          {"onConnectionStateChanged", "([BI)V", &method_onConnectionStateChanged},
          {"onVolumeStateChangeRequest", "(II)V", &method_onVolumeStateChangeRequest},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/vcp/VcpRendererNativeCallback", javaMethods);

  return 0;
}

}  // namespace android
