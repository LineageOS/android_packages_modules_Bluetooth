/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "BluetoothKeystoreServiceJni"

#include <bluetooth/log.h>
#include <jni.h>

#include <cstring>
#include <future>
#include <map>
#include <mutex>
#include <shared_mutex>
#include <string>

#include "btif/include/btif_common.h"
#include "com_android_bluetooth.h"
#include "gd/os/parameter_provider.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_keystore.h"
#include "main/shim/config.h"

using bluetooth::bluetooth_keystore::BluetoothKeystoreInterface;

namespace android {

const int CONFIG_COMPARE_ALL_PASS = 0b11;

static void setEncryptKeyOrRemoveKeyCallback(const std::string prefixString,
                                             const std::string decryptedString);
static std::string getKeyCallback(const std::string prefixString);

class BluetoothKeystoreInterfaceImpl : public BluetoothKeystoreInterface {
public:
  ~BluetoothKeystoreInterfaceImpl() override = default;

  bool set_encrypt_key_or_remove_key(std::string prefix, std::string decryptedString) override {
    log::verbose("prefix: {}", prefix);

    cache_[prefix] = decryptedString;

    do_in_jni_thread(base::BindOnce(setEncryptKeyOrRemoveKeyCallback, prefix, decryptedString));
    return true;
  }

  std::string get_key(std::string prefix) override {
    log::verbose("prefix: {}", prefix);

    auto it = cache_.find(prefix);
    if (it != cache_.end()) {
      return it->second;
    }

    std::string decryptedString;

    if (is_on_jni_thread()) {
      decryptedString = getKeyCallback(prefix);
    } else {
      std::promise<std::string> promise;
      std::future<std::string> future = promise.get_future();
      do_in_jni_thread(base::BindOnce(
              [](std::string prefix, std::promise<std::string> promise) {
                promise.set_value(getKeyCallback(prefix));
              },
              prefix, std::move(promise)));
      decryptedString = future.get();
    }

    cache_[prefix] = decryptedString;
    log::verbose("get key from bluetoothkeystore.");
    return decryptedString;
  }

  void clear_map() override {
    log::verbose("");
    cache_.clear();
  }

private:
  std::unordered_map<std::string, std::string> cache_;
};

static jmethodID method_setEncryptKeyOrRemoveKeyCallback;
static jmethodID method_getKeyCallback;

static std::unique_ptr<BluetoothKeystoreInterfaceImpl> bluetoothKeystoreInstance;
static std::shared_timed_mutex interface_mutex;

static jobject mCallbacksObj = nullptr;
static std::shared_timed_mutex callbacks_mutex;

static void setEncryptKeyOrRemoveKeyCallback(const std::string prefixString,
                                             const std::string decryptedString) {
  log::info("");

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
    return;
  }

  jstring j_prefixString = sCallbackEnv->NewStringUTF(prefixString.c_str());
  jstring j_decryptedString = sCallbackEnv->NewStringUTF(decryptedString.c_str());

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_setEncryptKeyOrRemoveKeyCallback,
                               j_prefixString, j_decryptedString);
}

static std::string getKeyCallback(const std::string prefixString) {
  log::info("");

  std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
    return "";
  }

  jstring j_prefixString = sCallbackEnv->NewStringUTF(prefixString.c_str());

  jstring j_decrypt_str = (jstring)sCallbackEnv->CallObjectMethod(
          mCallbacksObj, method_getKeyCallback, j_prefixString);

  if (j_decrypt_str == nullptr) {
    log::error("Got a null decrypt_str");
    return "";
  }

  const char* value = sCallbackEnv->GetStringUTFChars(j_decrypt_str, nullptr);
  std::string ret(value);
  sCallbackEnv->ReleaseStringUTFChars(j_decrypt_str, value);

  return ret;
}

static void initNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  if (mCallbacksObj != nullptr) {
    log::info("Cleaning up BluetoothKeystore callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  mCallbacksObj = env->NewGlobalRef(object);
  log::assert_that(mCallbacksObj != nullptr,
                   "Failed to allocate Global Ref for BluetoothKeystore Callbacks");

  if (bluetoothKeystoreInstance == nullptr) {
    bluetoothKeystoreInstance = std::make_unique<BluetoothKeystoreInterfaceImpl>();
  }

  bluetooth::os::ParameterProvider::SetBtKeystoreInterface(bluetoothKeystoreInstance.get());
  bluetooth::os::ParameterProvider::SetCommonCriteriaConfigCompareResult(CONFIG_COMPARE_ALL_PASS);
  do_in_jni_thread(base::BindOnce(
          []() { bluetooth::shim::BtifConfigInterface::ConvertEncryptOrDecryptKeyIfNeeded(); }));
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  if (mCallbacksObj != nullptr) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }
}

int register_com_android_bluetooth_btservice_BluetoothKeystore(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative", "()V", (void*)initNative},
          {"cleanupNative", "()V", (void*)cleanupNative},
  };
  const int result = REGISTER_NATIVE_METHODS(env,
                                             "com/android/bluetooth/btservice/bluetoothkeystore/"
                                             "BluetoothKeystoreNativeInterface",
                                             methods);
  if (result != 0) {
    return result;
  }

  const JNIJavaMethod javaMethods[] = {
          {"setEncryptKeyOrRemoveKeyCallback", "(Ljava/lang/String;Ljava/lang/String;)V",
           &method_setEncryptKeyOrRemoveKeyCallback},
          {"getKeyCallback", "(Ljava/lang/String;)Ljava/lang/String;", &method_getKeyCallback},
  };
  GET_JAVA_METHODS(env,
                   "com/android/bluetooth/btservice/bluetoothkeystore/"
                   "BluetoothKeystoreNativeInterface",
                   javaMethods);

  return 0;
}

}  // namespace android
