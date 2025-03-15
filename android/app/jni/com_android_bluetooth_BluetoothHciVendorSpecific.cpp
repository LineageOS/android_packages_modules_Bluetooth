/*
 * Copyright (C) 2024 The Android Open Source Project
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

#define LOG_TAG "BluetoothHciVendorSpecificJni"

#include <shared_mutex>

#include "btif/include/btif_hci_vs.h"
#include "com_android_bluetooth.h"
#include "hardware/bt_hci_vs.h"

using bluetooth::hci_vs::BluetoothHciVendorSpecificInterface;
using bluetooth::hci_vs::Cookie;

namespace android {

static std::shared_timed_mutex interface_mutex;
static std::shared_timed_mutex callbacks_mutex;

static jmethodID method_onCommandStatus;
static jmethodID method_onCommandComplete;
static jmethodID method_onEvent;
static jobject mCallbacksObj = nullptr;

class BluetoothHciVendorSpecificCallbacksImpl
    : public bluetooth::hci_vs::BluetoothHciVendorSpecificCallbacks {
public:
  ~BluetoothHciVendorSpecificCallbacksImpl() = default;

  void onCommandStatus(uint16_t ocf, uint8_t status, Cookie cookie) override {
    log::info("");
    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);

    CallbackEnv callbackEnv(__func__);
    if (!callbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    auto j_cookie = toJByteArray(callbackEnv.get(), cookie);
    if (!j_cookie.get()) {
      log::error("Error while allocating byte array for cookie");
      return;
    }

    callbackEnv->CallVoidMethod(mCallbacksObj, method_onCommandStatus, (jint)ocf, (jint)status,
                                j_cookie.get());
  }

  void onCommandComplete(uint16_t ocf, std::vector<uint8_t> return_parameters,
                         Cookie cookie) override {
    log::info("");
    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);

    CallbackEnv callbackEnv(__func__);
    if (!callbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    auto j_return_parameters = toJByteArray(callbackEnv.get(), return_parameters);
    auto j_cookie = toJByteArray(callbackEnv.get(), cookie);
    if (!j_return_parameters.get() || !j_cookie.get()) {
      log::error("Error while allocating byte array for return parameters or cookie");
      return;
    }

    callbackEnv->CallVoidMethod(mCallbacksObj, method_onCommandComplete, (jint)ocf,
                                j_return_parameters.get(), j_cookie.get());
  }

  void onEvent(uint8_t code, std::vector<uint8_t> data) override {
    log::info("");
    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);

    CallbackEnv callbackEnv(__func__);
    if (!callbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    auto j_data = toJByteArray(callbackEnv.get(), data);
    if (!j_data.get()) {
      log::error("Error while allocating byte array for event data");
      return;
    }

    callbackEnv->CallVoidMethod(mCallbacksObj, method_onEvent, (jint)code, j_data.get());
  }

private:
  template <typename T>
  static ScopedLocalRef<jbyteArray> toJByteArray(JNIEnv* env, const T& src) {
    ScopedLocalRef<jbyteArray> dst(env, env->NewByteArray(src.size()));
    if (dst.get()) {
      env->SetByteArrayRegion(dst.get(), 0, src.size(), reinterpret_cast<const jbyte*>(src.data()));
    }
    return dst;
  }
};

static BluetoothHciVendorSpecificInterface* sBluetoothHciVendorSpecificInterface = nullptr;
static BluetoothHciVendorSpecificCallbacksImpl sBluetoothHciVendorSpecificCallbacks;

static void initNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  if (sBluetoothHciVendorSpecificInterface != nullptr) {
    log::info("Cleaning up BluetoothHciVendorSpecific Interface before initializing...");
    sBluetoothHciVendorSpecificInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    log::info("Cleaning up BluetoothHciVendorSpecific callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  if ((mCallbacksObj = env->NewGlobalRef(object)) == nullptr) {
    log::error("Failed to allocate Global Ref for BluetoothHciVendorSpecific Callbacks");
    return;
  }

  sBluetoothHciVendorSpecificInterface =
          bluetooth::hci_vs::getBluetoothHciVendorSpecificInterface();
  if (sBluetoothHciVendorSpecificInterface == nullptr) {
    log::error("Failed to get BluetoothHciVendorSpecific Interface");
    return;
  }

  sBluetoothHciVendorSpecificInterface->init(&sBluetoothHciVendorSpecificCallbacks);
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  if (sBluetoothHciVendorSpecificInterface != nullptr) {
    sBluetoothHciVendorSpecificInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }
}

static void sendCommandNative(JNIEnv* env, jobject /* obj */, jint ocf, jbyteArray parametersArray,
                              jbyteArray uuidArray) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  log::verbose("");
  if (!sBluetoothHciVendorSpecificInterface) {
    return;
  }

  jbyte* pParameters = env->GetByteArrayElements(parametersArray, NULL);
  jbyte* pUuid = env->GetByteArrayElements(uuidArray, NULL);
  if (pParameters && pUuid && env->GetArrayLength(uuidArray) == 16) {
    std::vector<uint8_t> parameters(
            reinterpret_cast<uint8_t*>(pParameters),
            reinterpret_cast<uint8_t*>(pParameters + env->GetArrayLength(parametersArray)));

    Cookie cookie;
    std::memcpy(cookie.data(), reinterpret_cast<const uint8_t*>(pUuid), 16);

    if (!sBluetoothHciVendorSpecificInterface) {
      return;
    }
    sBluetoothHciVendorSpecificInterface->sendCommand(ocf, parameters, cookie);

  } else {
    jniThrowIOException(env, EINVAL);
  }

  if (pParameters) {
    env->ReleaseByteArrayElements(parametersArray, pParameters, 0);
  }

  if (pUuid) {
    env->ReleaseByteArrayElements(uuidArray, pUuid, 0);
  }
}

int register_com_android_bluetooth_btservice_BluetoothHciVendorSpecific(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative", "()V", reinterpret_cast<void*>(initNative)},
          {"cleanupNative", "()V", reinterpret_cast<void*>(cleanupNative)},
          {"sendCommandNative", "(I[B[B)V", reinterpret_cast<void*>(sendCommandNative)},
  };
  const int result = REGISTER_NATIVE_METHODS(
          env, "com/android/bluetooth/btservice/BluetoothHciVendorSpecificNativeInterface",
          methods);
  if (result != 0) {
    return result;
  }

  const JNIJavaMethod javaMethods[] = {
          {"onCommandStatus", "(II[B)V", &method_onCommandStatus},
          {"onCommandComplete", "(I[B[B)V", &method_onCommandComplete},
          {"onEvent", "(I[B)V", &method_onEvent},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/btservice/BluetoothHciVendorSpecificNativeInterface",
                   javaMethods);

  return 0;
}

}  // namespace android
