/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#define LOG_TAG "BluetoothVolumeControlServiceJni"

#include <aics/api.h>
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
#include <string>

#include "com_android_bluetooth.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_vcp_controller.h"

using bluetooth::aics::GainMode;
using bluetooth::aics::Mute;
using bluetooth::vcp::ConnectionState;
using bluetooth::vcp::VolumeControllerCallbacks;
using bluetooth::vcp::VolumeControllerInterface;
using bluetooth::vcp::VolumeInputStatus;
using bluetooth::vcp::VolumeInputType;

namespace android {
static jmethodID method_onConnectionStateChanged;
static jmethodID method_onVolumeStateChanged;
static jmethodID method_onGroupVolumeStateChanged;
static jmethodID method_onDeviceAvailable;
static jmethodID method_onExtAudioOutVolumeOffsetChanged;
static jmethodID method_onExtAudioOutLocationChanged;
static jmethodID method_onExtAudioOutDescriptionChanged;
static jmethodID method_onExtAudioInStateChanged;
static jmethodID method_onExtAudioInSetGainSettingFailed;
static jmethodID method_onExtAudioInSetMuteFailed;
static jmethodID method_onExtAudioInSetGainModeFailed;
static jmethodID method_onExtAudioInStatusChanged;
static jmethodID method_onExtAudioInTypeChanged;
static jmethodID method_onExtAudioInGainSettingPropertiesChanged;
static jmethodID method_onExtAudioInDescriptionChanged;

static VolumeControllerInterface* sVolumeControllerInterface = nullptr;
static std::shared_timed_mutex interface_mutex;

static jobject mCallbacksObj = nullptr;
static std::shared_timed_mutex callbacks_mutex;

static jfieldID sCallbacksField;

class VolumeControllerCallbacksImpl : public VolumeControllerCallbacks {
public:
  ~VolumeControllerCallbacksImpl() = default;
  void OnConnectionState(ConnectionState state, const RawAddress& bd_addr) override {
    log::info("state:{}, addr: {}", static_cast<int>(state), bd_addr.ToRedactedStringForLogging());

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectionStateChanged, (jint)state,
                                 addr.get());
  }

  void OnVolumeStateChanged(const RawAddress& bd_addr, uint8_t volume, bool mute, uint8_t flags,
                            bool isAutonomous) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onVolumeStateChanged, (jint)volume,
                                 (jboolean)mute, (jint)flags, addr.get(), (jboolean)isAutonomous);
  }

  void OnGroupVolumeStateChanged(int group_id, uint8_t volume, bool mute,
                                 bool isAutonomous) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGroupVolumeStateChanged, (jint)volume,
                                 (jboolean)mute, group_id, (jboolean)isAutonomous);
  }

  void OnDeviceAvailable(const RawAddress& bd_addr, int group_id, uint8_t num_offsets,
                         uint8_t num_inputs) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onDeviceAvailable, (jint)group_id,
                                 (jint)num_offsets, (jint)num_inputs, addr.get());
  }

  void OnExtAudioOutVolumeOffsetChanged(const RawAddress& bd_addr, uint8_t ext_output_id,
                                        int16_t offset) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioOutVolumeOffsetChanged,
                                 (jint)ext_output_id, (jint)offset, addr.get());
  }

  void OnExtAudioOutLocationChanged(const RawAddress& bd_addr, uint8_t ext_output_id,
                                    uint32_t location) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioOutLocationChanged,
                                 (jint)ext_output_id, (jint)location, addr.get());
  }

  void OnExtAudioOutDescriptionChanged(const RawAddress& bd_addr, uint8_t ext_output_id,
                                       std::string descr) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    jstring description = sCallbackEnv->NewStringUTF(descr.c_str());
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioOutDescriptionChanged,
                                 (jint)ext_output_id, description, addr.get());
  }

  void OnExtAudioInStateChanged(const RawAddress& bd_addr, uint8_t ext_input_id,
                                int8_t gain_setting, Mute mute, GainMode gain_mode) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioInStateChanged, (jint)ext_input_id,
                                 (jint)gain_setting, (jint)mute, (jint)gain_mode, addr.get());
  }

  void OnExtAudioInSetGainSettingFailed(const RawAddress& bd_addr, uint8_t ext_input_id) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioInSetGainSettingFailed,
                                 (jint)ext_input_id, addr.get());
  }

  void OnExtAudioInSetMuteFailed(const RawAddress& bd_addr, uint8_t ext_input_id) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioInSetMuteFailed,
                                 (jint)ext_input_id, addr.get());
  }
  void OnExtAudioInSetGainModeFailed(const RawAddress& bd_addr, uint8_t ext_input_id) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioInSetGainModeFailed,
                                 (jint)ext_input_id, addr.get());
  }

  void OnExtAudioInStatusChanged(const RawAddress& bd_addr, uint8_t ext_input_id,
                                 VolumeInputStatus status) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioInStatusChanged,
                                 (jint)ext_input_id, (jint)status, addr.get());
  }

  void OnExtAudioInTypeChanged(const RawAddress& bd_addr, uint8_t ext_input_id,
                               VolumeInputType type) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioInTypeChanged, (jint)ext_input_id,
                                 (jint)type, addr.get());
  }

  void OnExtAudioInGainSettingPropertiesChanged(const RawAddress& bd_addr, uint8_t ext_input_id,
                                                uint8_t unit, int8_t min, int8_t max) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioInGainSettingPropertiesChanged,
                                 (jint)ext_input_id, (jint)unit, (jint)min, (jint)max, addr.get());
  }

  void OnExtAudioInDescriptionChanged(const RawAddress& bd_addr, uint8_t ext_input_id,
                                      std::string description, bool is_writable) override {
    log::info("");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    jstring jdescription = sCallbackEnv->NewStringUTF(description.c_str());
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExtAudioInDescriptionChanged,
                                 (jint)ext_input_id, jdescription, (jboolean)is_writable,
                                 addr.get());
  }
};

static VolumeControllerCallbacksImpl sVolumeControllerCallbacks;

static void initNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sVolumeControllerInterface != nullptr) {
    log::info("Cleaning up VolumeControl Interface before initializing...");
    sVolumeControllerInterface->Cleanup();
    sVolumeControllerInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    log::info("Cleaning up VolumeControl callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  if ((mCallbacksObj = env->NewGlobalRef(env->GetObjectField(object, sCallbacksField))) ==
      nullptr) {
    log::fatal("Failed to allocate Global Ref for Volume control Callbacks");
  }

  sVolumeControllerInterface =
          const_cast<VolumeControllerInterface*>(reinterpret_cast<const VolumeControllerInterface*>(
                  btInf->get_profile_interface(BT_PROFILE_VCP_CONTROLLER_ID)));

  if (sVolumeControllerInterface == nullptr) {
    log::error("Failed to get Bluetooth Volume Control Interface");
    return;
  }

  sVolumeControllerInterface->Init(&sVolumeControllerCallbacks);
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sVolumeControllerInterface != nullptr) {
    sVolumeControllerInterface->Cleanup();
    sVolumeControllerInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }
}

static jboolean connectVolumeControlNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);

  if (!sVolumeControllerInterface) {
    log::error("Failed to get the Bluetooth Volume Control Interface");
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->Connect(bd_addr);
  return JNI_TRUE;
}

static jboolean disconnectVolumeControlNative(JNIEnv* env, jobject /* object */,
                                              jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);

  if (!sVolumeControllerInterface) {
    log::error("Failed to get the Bluetooth Volume Control Interface");
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->Disconnect(bd_addr);
  return JNI_TRUE;
}

static void setVolumeNative(JNIEnv* env, jobject /* object */, jbyteArray address, jint volume) {
  if (!sVolumeControllerInterface) {
    log::error("Failed to get the Bluetooth Volume Control Interface");
    return;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->SetVolume(bd_addr, volume);
}

static void setGroupVolumeNative(JNIEnv* /* env */, jobject /* object */, jint group_id,
                                 jint volume) {
  if (!sVolumeControllerInterface) {
    log::error("Failed to get the Bluetooth Volume Control Interface");
    return;
  }

  sVolumeControllerInterface->SetVolume(group_id, volume);
}

static void muteNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  if (!sVolumeControllerInterface) {
    log::error("Failed to get the Bluetooth Volume Control Interface");
    return;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->Mute(bd_addr);
}

static void muteGroupNative(JNIEnv* /* env */, jobject /* object */, jint group_id) {
  if (!sVolumeControllerInterface) {
    log::error("Failed to get the Bluetooth Volume Control Interface");
    return;
  }
  sVolumeControllerInterface->Mute(group_id);
}

static void unmuteNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  if (!sVolumeControllerInterface) {
    log::error("Failed to get the Bluetooth Volume Control Interface");
    return;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->Unmute(bd_addr);
}

static void unmuteGroupNative(JNIEnv* /* env */, jobject /* object */, jint group_id) {
  if (!sVolumeControllerInterface) {
    log::error("Failed to get the Bluetooth Volume Control Interface");
    return;
  }
  sVolumeControllerInterface->Unmute(group_id);
}

/* Native methods for exterbak audio outputs */
static jboolean getExtAudioOutVolumeOffsetNative(JNIEnv* env, jobject /* object */,
                                                 jbyteArray address, jint ext_output_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->GetExtAudioOutVolumeOffset(bd_addr, ext_output_id);
  return JNI_TRUE;
}

static jboolean setExtAudioOutVolumeOffsetNative(JNIEnv* env, jobject /* object */,
                                                 jbyteArray address, jint ext_output_id,
                                                 jint offset) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->SetExtAudioOutVolumeOffset(bd_addr, ext_output_id, offset);
  return JNI_TRUE;
}

static jboolean getExtAudioOutLocationNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                             jint ext_output_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->GetExtAudioOutLocation(bd_addr, ext_output_id);
  return JNI_TRUE;
}

static jboolean setExtAudioOutLocationNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                             jint ext_output_id, jint location) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->SetExtAudioOutLocation(bd_addr, ext_output_id, location);
  return JNI_TRUE;
}

static jboolean getExtAudioOutDescriptionNative(JNIEnv* env, jobject /* object */,
                                                jbyteArray address, jint ext_output_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->GetExtAudioOutDescription(bd_addr, ext_output_id);
  return JNI_TRUE;
}

static jboolean setExtAudioOutDescriptionNative(JNIEnv* env, jobject /* object */,
                                                jbyteArray address, jint ext_output_id,
                                                jstring descr) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  std::string description;
  if (descr != nullptr) {
    const char* value = env->GetStringUTFChars(descr, nullptr);
    description = std::string(value);
    env->ReleaseStringUTFChars(descr, value);
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->SetExtAudioOutDescription(bd_addr, ext_output_id, description);
  return JNI_TRUE;
}

/* Native methods for external audio inputs */
static jboolean getExtAudioInStateNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                         jint ext_input_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->GetExtAudioInState(bd_addr, ext_input_id);
  return JNI_TRUE;
}

static jboolean getExtAudioInStatusNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                          jint ext_input_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->GetExtAudioInStatus(bd_addr, ext_input_id);
  return JNI_TRUE;
}

static jboolean getExtAudioInTypeNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                        jint ext_input_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->GetExtAudioInType(bd_addr, ext_input_id);
  return JNI_TRUE;
}

static jboolean getExtAudioInGainPropsNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                             jint ext_input_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->GetExtAudioInGainProps(bd_addr, ext_input_id);
  return JNI_TRUE;
}

static jboolean getExtAudioInDescriptionNative(JNIEnv* env, jobject /* object */,
                                               jbyteArray address, jint ext_input_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  sVolumeControllerInterface->GetExtAudioInDescription(bd_addr, ext_input_id);
  return JNI_TRUE;
}

static jboolean setExtAudioInDescriptionNative(JNIEnv* env, jobject /* object */,
                                               jbyteArray address, jint ext_input_id,
                                               jstring descr) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  std::string description;
  if (descr != nullptr) {
    const char* value = env->GetStringUTFChars(descr, nullptr);
    description = std::string(value);
    env->ReleaseStringUTFChars(descr, value);
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  bool ret =
          sVolumeControllerInterface->SetExtAudioInDescription(bd_addr, ext_input_id, description);
  return ret ? JNI_TRUE : JNI_FALSE;
}

static jboolean setExtAudioInGainSettingNative(JNIEnv* env, jobject /* object */,
                                               jbyteArray address, jint ext_input_id,
                                               jint gain_setting) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  bool ret =
          sVolumeControllerInterface->SetExtAudioInGainSetting(bd_addr, ext_input_id, gain_setting);
  return ret ? JNI_TRUE : JNI_FALSE;
}

static jboolean setExtAudioInGainModeNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                            jint ext_input_id, jint gain_mode) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  bool ret = sVolumeControllerInterface->SetExtAudioInGainMode(
          bd_addr, ext_input_id, bluetooth::aics::parseGainModeField(gain_mode));
  return ret ? JNI_TRUE : JNI_FALSE;
}

static jboolean setExtAudioInMuteNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                        jint ext_input_id, jint mute) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sVolumeControllerInterface) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  bool ret = sVolumeControllerInterface->SetExtAudioInMute(bd_addr, ext_input_id,
                                                           bluetooth::aics::parseMuteField(mute));
  return ret ? JNI_TRUE : JNI_FALSE;
}

// JNI functions defined in VolumeControlNativeInterface
int register_com_android_bluetooth_vc(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative", "()V", reinterpret_cast<void*>(initNative)},
          {"cleanupNative", "()V", reinterpret_cast<void*>(cleanupNative)},
          {"connectVolumeControlNative", "([B)Z",
           reinterpret_cast<void*>(connectVolumeControlNative)},
          {"disconnectVolumeControlNative", "([B)Z",
           reinterpret_cast<void*>(disconnectVolumeControlNative)},
          {"setVolumeNative", "([BI)V", reinterpret_cast<void*>(setVolumeNative)},
          {"setGroupVolumeNative", "(II)V", reinterpret_cast<void*>(setGroupVolumeNative)},
          {"muteNative", "([B)V", reinterpret_cast<void*>(muteNative)},
          {"muteGroupNative", "(I)V", reinterpret_cast<void*>(muteGroupNative)},
          {"unmuteNative", "([B)V", reinterpret_cast<void*>(unmuteNative)},
          {"unmuteGroupNative", "(I)V", reinterpret_cast<void*>(unmuteGroupNative)},
          {"getExtAudioOutVolumeOffsetNative", "([BI)Z",
           reinterpret_cast<void*>(getExtAudioOutVolumeOffsetNative)},
          {"setExtAudioOutVolumeOffsetNative", "([BII)Z",
           reinterpret_cast<void*>(setExtAudioOutVolumeOffsetNative)},
          {"getExtAudioOutLocationNative", "([BI)Z",
           reinterpret_cast<void*>(getExtAudioOutLocationNative)},
          {"setExtAudioOutLocationNative", "([BII)Z",
           reinterpret_cast<void*>(setExtAudioOutLocationNative)},
          {"getExtAudioOutDescriptionNative", "([BI)Z",
           reinterpret_cast<void*>(getExtAudioOutDescriptionNative)},
          {"setExtAudioOutDescriptionNative", "([BILjava/lang/String;)Z",
           reinterpret_cast<void*>(setExtAudioOutDescriptionNative)},
          {"getExtAudioInStateNative", "([BI)Z", reinterpret_cast<void*>(getExtAudioInStateNative)},
          {"getExtAudioInStatusNative", "([BI)Z",
           reinterpret_cast<void*>(getExtAudioInStatusNative)},
          {"getExtAudioInTypeNative", "([BI)Z", reinterpret_cast<void*>(getExtAudioInTypeNative)},
          {"getExtAudioInGainPropsNative", "([BI)Z",
           reinterpret_cast<void*>(getExtAudioInGainPropsNative)},
          {"getExtAudioInDescriptionNative", "([BI)Z",
           reinterpret_cast<void*>(getExtAudioInDescriptionNative)},
          {"setExtAudioInDescriptionNative", "([BILjava/lang/String;)Z",
           reinterpret_cast<void*>(setExtAudioInDescriptionNative)},
          {"setExtAudioInGainSettingNative", "([BII)Z",
           reinterpret_cast<void*>(setExtAudioInGainSettingNative)},
          {"setExtAudioInGainModeNative", "([BII)Z",
           reinterpret_cast<void*>(setExtAudioInGainModeNative)},
          {"setExtAudioInMuteNative", "([BII)Z", reinterpret_cast<void*>(setExtAudioInMuteNative)},
  };
  const char* jniNativeInterfaceClass = "com/android/bluetooth/vc/VolumeControlNativeInterface";
  const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
  if (result != 0) {
    return result;
  }

  sCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

  // Client callback functions defined in VolumeControlNativeCallback
  const JNIJavaMethod javaMethods[] = {
          {"onConnectionStateChanged", "(I[B)V", &method_onConnectionStateChanged},
          {"onVolumeStateChanged", "(IZI[BZ)V", &method_onVolumeStateChanged},
          {"onGroupVolumeStateChanged", "(IZIZ)V", &method_onGroupVolumeStateChanged},
          {"onDeviceAvailable", "(III[B)V", &method_onDeviceAvailable},
          {"onExtAudioOutVolumeOffsetChanged", "(II[B)V", &method_onExtAudioOutVolumeOffsetChanged},
          {"onExtAudioOutLocationChanged", "(II[B)V", &method_onExtAudioOutLocationChanged},
          {"onExtAudioOutDescriptionChanged", "(ILjava/lang/String;[B)V",
           &method_onExtAudioOutDescriptionChanged},
          {"onExtAudioInStateChanged", "(IIII[B)V", &method_onExtAudioInStateChanged},
          {"onExtAudioInSetGainSettingFailed", "(I[B)V", &method_onExtAudioInSetGainSettingFailed},
          {"onExtAudioInSetMuteFailed", "(I[B)V", &method_onExtAudioInSetMuteFailed},
          {"onExtAudioInSetGainModeFailed", "(I[B)V", &method_onExtAudioInSetGainModeFailed},
          {"onExtAudioInStatusChanged", "(II[B)V", &method_onExtAudioInStatusChanged},
          {"onExtAudioInTypeChanged", "(II[B)V", &method_onExtAudioInTypeChanged},
          {"onExtAudioInGainSettingPropertiesChanged", "(IIII[B)V",
           &method_onExtAudioInGainSettingPropertiesChanged},
          {"onExtAudioInDescriptionChanged", "(ILjava/lang/String;Z[B)V",
           &method_onExtAudioInDescriptionChanged},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/vc/VolumeControlNativeCallback", javaMethods);

  return 0;
}
}  // namespace android
