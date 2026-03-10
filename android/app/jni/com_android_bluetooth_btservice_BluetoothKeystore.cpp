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

using bluetooth::bluetooth_keystore::BluetoothKeystoreCallbacks;
using bluetooth::bluetooth_keystore::BluetoothKeystoreInterface;

namespace bluetooth {
namespace bluetooth_keystore {

class BluetoothKeystoreInterfaceImpl;
static std::unique_ptr<BluetoothKeystoreInterface> bluetoothKeystoreInstance;
const int CONFIG_COMPARE_ALL_PASS = 0b11;

class BluetoothKeystoreInterfaceImpl
    : public bluetooth::bluetooth_keystore::BluetoothKeystoreInterface {
  ~BluetoothKeystoreInterfaceImpl() override = default;

  void init(BluetoothKeystoreCallbacks* callbacks) override {
    log::verbose("");
    this->callbacks = callbacks;

    bluetooth::os::ParameterProvider::SetCommonCriteriaConfigCompareResult(CONFIG_COMPARE_ALL_PASS);
    ConvertEncryptOrDecryptKeyIfNeeded();
  }

  void ConvertEncryptOrDecryptKeyIfNeeded() {
    log::verbose("");
    if (!callbacks) {
      log::info("callback isn't ready.");
      return;
    }
    do_in_jni_thread(base::BindOnce(
            []() { shim::BtifConfigInterface::ConvertEncryptOrDecryptKeyIfNeeded(); }));
  }

  bool set_encrypt_key_or_remove_key(std::string prefix, std::string decryptedString) override {
    log::verbose("prefix: {}", prefix);

    if (!callbacks) {
      log::warn("callback isn't ready. prefix: {}", prefix);
      return false;
    }

    // Save the value into a map.
    key_map[prefix] = decryptedString;

    do_in_jni_thread(base::BindOnce(&bluetooth::bluetooth_keystore::BluetoothKeystoreCallbacks::
                                            set_encrypt_key_or_remove_key,
                                    base::Unretained(callbacks), prefix, decryptedString));
    return true;
  }

  std::string get_key(std::string prefix) override {
    log::verbose("prefix: {}", prefix);

    if (!callbacks) {
      log::warn("callback isn't ready. prefix: {}", prefix);
      return "";
    }

    std::string decryptedString;
    // try to find the key.
    std::map<std::string, std::string>::iterator iter = key_map.find(prefix);
    if (iter == key_map.end()) {
      decryptedString = callbacks->get_key(prefix);
      // Save the value into a map.
      key_map[prefix] = decryptedString;
      log::verbose("get key from bluetoothkeystore.");
    } else {
      decryptedString = iter->second;
    }
    return decryptedString;
  }

  void clear_map() override {
    log::verbose("");

    std::map<std::string, std::string> empty_map;
    key_map.swap(empty_map);
    key_map.clear();
  }

private:
  BluetoothKeystoreCallbacks* callbacks = nullptr;
  std::map<std::string, std::string> key_map;
};

static BluetoothKeystoreInterface* getBluetoothKeystoreInterface() {
  if (!bluetoothKeystoreInstance) {
    bluetoothKeystoreInstance.reset(new BluetoothKeystoreInterfaceImpl());
  }

  return bluetoothKeystoreInstance.get();
}

}  // namespace bluetooth_keystore
}  // namespace bluetooth

namespace android {
static jmethodID method_setEncryptKeyOrRemoveKeyCallback;
static jmethodID method_getKeyCallback;

static BluetoothKeystoreInterface* sBluetoothKeystoreInterface = nullptr;
static std::shared_timed_mutex interface_mutex;

static jobject mCallbacksObj = nullptr;
static std::shared_timed_mutex callbacks_mutex;

class BluetoothKeystoreCallbacksImpl
    : public bluetooth::bluetooth_keystore::BluetoothKeystoreCallbacks {
public:
  ~BluetoothKeystoreCallbacksImpl() = default;

  void set_encrypt_key_or_remove_key(const std::string prefixString,
                                     const std::string decryptedString) override {
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

  std::string get_key(const std::string prefixString) override {
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
};

static BluetoothKeystoreCallbacksImpl sBluetoothKeystoreCallbacks;

static void initNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sBluetoothKeystoreInterface != nullptr) {
    log::info("Cleaning up BluetoothKeystore Interface before initializing...");
    sBluetoothKeystoreInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    log::info("Cleaning up BluetoothKeystore callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  if ((mCallbacksObj = env->NewGlobalRef(object)) == nullptr) {
    log::fatal("Failed to allocate Global Ref for BluetoothKeystore Callbacks");
  }

  sBluetoothKeystoreInterface = bluetooth::bluetooth_keystore::getBluetoothKeystoreInterface();
  if (sBluetoothKeystoreInterface == nullptr) {
    log::error("Failed to get BluetoothKeystore Interface");
    return;
  }

  bluetooth::os::ParameterProvider::SetBtKeystoreInterface(sBluetoothKeystoreInterface);
  sBluetoothKeystoreInterface->init(&sBluetoothKeystoreCallbacks);
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sBluetoothKeystoreInterface != nullptr) {
    sBluetoothKeystoreInterface = nullptr;
  }

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
