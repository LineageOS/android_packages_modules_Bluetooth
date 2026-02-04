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

#define LOG_TAG "BluetoothLeAudioPeripheralServiceJni"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_local_ref.h>

#include <cctype>
#include <cerrno>
#include <cstring>
#include <shared_mutex>

#include "com_android_bluetooth.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_le_audio_server.h"

using bluetooth::le_audio::GattConnectionState;
using bluetooth::le_audio::LeAudioServerCallbacks;
using bluetooth::le_audio::LeAudioServerCodecConfig;
using bluetooth::le_audio::LeAudioServerInterface;

namespace android {

namespace {
// Note: This JNI helper class can be extracted and reused by other services if needed.
template <typename Interface, typename Callbacks>
class JniProfile {
public:
  // Constructor takes a pointer to the static callbacks implementation
  explicit JniProfile(Callbacks* callbacks) : mStaticCallbacks_(*callbacks) {}

  // Initializes the native profile interface
  template <typename... Args>
  void init(JNIEnv* env, jobject object, jfieldID callbacksField, const char* profile_id,
            const char* profile_name, Args&&... args) {
    std::unique_lock<std::shared_timed_mutex> lock(mMutex);

    const bt_interface_t* btInf = getBluetoothInterface();
    if (btInf == nullptr) {
      log::error("{}: Bluetooth module is not loaded", profile_name);
      return;
    }

    if (mInterface_ != nullptr) {
      log::info("{}: Cleaning up before initializing...", profile_name);
      mInterface_->Cleanup();
      mInterface_ = nullptr;
    }

    if (mCallbacksObj_ != nullptr) {
      log::info("{}: Cleaning up callback object", profile_name);
      env->DeleteGlobalRef(mCallbacksObj_);
      mCallbacksObj_ = nullptr;
    }

    jobject callbacks_obj = object;
    if (callbacksField != nullptr) {
      callbacks_obj = env->GetObjectField(object, callbacksField);
    }

    if ((mCallbacksObj_ = env->NewGlobalRef(callbacks_obj)) == nullptr) {
      log::error("{}: Failed to allocate Global Ref for Callbacks", profile_name);
      return;
    }

    mInterface_ = const_cast<Interface*>(
            reinterpret_cast<const Interface*>(btInf->get_profile_interface(profile_id)));
    if (mInterface_ == nullptr) {
      log::error("{}: Failed to get Bluetooth Interface", profile_name);
      return;
    }

    mInterface_->Initialize(&mStaticCallbacks_, std::forward<Args>(args)...);
  }

  // Cleans up the native profile interface
  void cleanup(JNIEnv* env, const char* profile_name) {
    std::unique_lock<std::shared_timed_mutex> lock(mMutex);

    if (mInterface_ != nullptr) {
      log::info("{}: Cleaning up interface", profile_name);
      mInterface_->Cleanup();
      mInterface_ = nullptr;
    }

    if (mCallbacksObj_ != nullptr) {
      log::info("{}: Cleaning up callback object", profile_name);
      env->DeleteGlobalRef(mCallbacksObj_);
      mCallbacksObj_ = nullptr;
    }
  }

  // Provides a convenient way to safely execute a function with the interface
  // pointer. It handles locking and checking for a valid interface object.
  //
  // Usage:
  // getJniProfile().withInterface([&](Interface* iface) {
  //   iface->some_method(...);
  // });
  template <typename Func>
  void withInterface(Func f) {
    std::shared_lock<std::shared_timed_mutex> lock(mMutex);
    if (mInterface_ != nullptr) {
      f(mInterface_);
    }
  }

  // Provides a convenient way to safely execute JNI callbacks.
  // It handles getting the JNI environment, checking for a valid callback
  // object, and managing exceptions.
  //
  // Usage:
  // getJniProfile().withCallbackEnv(__func__, [&](JNIEnv* env, jobject callbacks) {
  //   env->CallVoidMethod(callbacks, ...);
  // });
  template <typename Func>
  void withCallbackEnv(const char* func_name, Func f) {
    CallbackEnv sCallbackEnv(func_name);
    std::shared_lock<std::shared_timed_mutex> lock(mMutex);
    if (!sCallbackEnv.valid() || mCallbacksObj_ == nullptr) {
      return;
    }
    f(sCallbackEnv.get(), mCallbacksObj_);
  }

private:
  Interface* mInterface_ = nullptr;
  jobject mCallbacksObj_ = nullptr;
  Callbacks& mStaticCallbacks_;
  std::shared_timed_mutex mMutex;
};
}  // namespace

static jmethodID method_onInitialized;
static jmethodID method_onConnectionStateChanged;
static jmethodID method_onStreamStartRequest;
static jmethodID method_onStreamStarted;
static jmethodID method_onSinkStreamReady;
static jmethodID method_onSourceStreamReady;
static jmethodID method_onStreamMetadataUpdated;
static jmethodID method_onStreamStopped;

static jfieldID sCallbacksField;

static jclass sStreamStartRequestInfoClass = nullptr;

static jmethodID sStreamStartRequestInfoConstructor = nullptr;
static jclass sCodecIdClass = nullptr;
static jmethodID sCodecIdConstructor = nullptr;
static jclass sAseDirectionClass = nullptr;
static jmethodID sAseDirectionFromMethod = nullptr;

static JniProfile<LeAudioServerInterface, LeAudioServerCallbacks>& getJniProfile();

class LeAudioServerCallbacksImpl : public LeAudioServerCallbacks {
public:
  ~LeAudioServerCallbacksImpl() = default;

  void OnInitialized(void) override {
    log::info("");
    getJniProfile().withCallbackEnv(__func__, [&](JNIEnv* env, jobject callbacks) {
      env->CallVoidMethod(callbacks, method_onInitialized);
    });
  }

  void OnConnectionStateChanged(const RawAddress& bd_addr, GattConnectionState state) override {
    log::info("state:{}, addr: {}", int(state), bd_addr.ToRedactedStringForLogging());

    getJniProfile().withCallbackEnv(__func__, [&](JNIEnv* env, jobject callbacks) {
      ScopedLocalRef<jbyteArray> addr(env, env->NewByteArray(sizeof(RawAddress)));
      if (!addr.get()) {
        log::error("Failed to new jbyteArray bd addr for connection state");
        return;
      }

      env->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress), (jbyte*)&bd_addr);
      env->CallVoidMethod(callbacks, method_onConnectionStateChanged, addr.get(), (jint)state);
    });
  }

  void OnStreamStartRequest(
          const RawAddress& address,
          const std::vector<bluetooth::le_audio::AseEnableRequest>& requests) override {
    log::info("");
    getJniProfile().withCallbackEnv(__func__, [&](JNIEnv* env, jobject callbacks) {
      ScopedLocalRef<jbyteArray> addr(env, env->NewByteArray(sizeof(RawAddress)));
      if (!addr.get()) {
        log::error("Failed to new jbyteArray bd addr for connection state");
        return;
      }
      env->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress), (jbyte*)&address);

      // Create an ArrayList of StreamStartRequestInfo objects
      jclass listClass = env->FindClass("java/util/ArrayList");
      if (listClass == nullptr) {
        log::error("Could not find java/util/ArrayList class");
        return;
      }
      jmethodID listConstructor = env->GetMethodID(listClass, "<init>", "()V");
      jobject requestList = env->NewObject(listClass, listConstructor);
      jmethodID listAdd = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");

      for (const auto& req : requests) {
        jobject codecId = env->NewObject(
                sCodecIdClass, sCodecIdConstructor, (jint)req.codec_id.coding_format,
                (jint)req.codec_id.vendor_company_id, (jint)req.codec_id.vendor_codec_id);

        jobject aseDirection = env->CallStaticObjectMethod(
                sAseDirectionClass, sAseDirectionFromMethod, (jint)req.direction);

        jobject requestInfo = env->NewObject(
                sStreamStartRequestInfoClass, sStreamStartRequestInfoConstructor, (jint)req.ase_id,
                aseDirection, (jint)req.audio_context_type, codecId, (jint)req.sample_rate_hz);
        env->CallBooleanMethod(requestList, listAdd, requestInfo);
        env->DeleteLocalRef(requestInfo);
        env->DeleteLocalRef(aseDirection);
        env->DeleteLocalRef(codecId);
      }

      env->CallVoidMethod(callbacks, method_onStreamStartRequest, addr.get(), requestList);
      env->DeleteLocalRef(requestList);
    });
  }

  void OnStreamStarted(const RawAddress& address, uint8_t ase_id,
                       uint32_t audio_context_type) override {
    log::info("");
    getJniProfile().withCallbackEnv(__func__, [&](JNIEnv* env, jobject callbacks) {
      ScopedLocalRef<jbyteArray> addr(env, env->NewByteArray(sizeof(RawAddress)));
      if (!addr.get()) {
        log::error("Failed to new jbyteArray bd addr for connection state");
        return;
      }
      env->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress), (jbyte*)&address);

      env->CallVoidMethod(callbacks, method_onStreamStarted, addr.get(), (jint)ase_id,
                          (jint)audio_context_type);
    });
  }

  void OnSinkStreamReady(const RawAddress& address) override {
    getJniProfile().withCallbackEnv(__func__, [&](JNIEnv* env, jobject callbacks) {
      ScopedLocalRef<jbyteArray> addr(env, env->NewByteArray(sizeof(RawAddress)));
      if (!addr.get()) {
        log::error("Failed to new jbyteArray bd addr for connection state");
        return;
      }
      env->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress), (jbyte*)&address);

      env->CallVoidMethod(callbacks, method_onSinkStreamReady, addr.get());
    });
  }

  void OnSourceStreamReady(const RawAddress& address) override {
    getJniProfile().withCallbackEnv(__func__, [&](JNIEnv* env, jobject callbacks) {
      ScopedLocalRef<jbyteArray> addr(env, env->NewByteArray(sizeof(RawAddress)));
      if (!addr.get()) {
        log::error("Failed to new jbyteArray bd addr for connection state");
        return;
      }
      env->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress), (jbyte*)&address);

      env->CallVoidMethod(callbacks, method_onSourceStreamReady, addr.get());
    });
  }

  void OnStreamMetadataUpdated(const RawAddress& address, uint8_t ase_id,
                               uint32_t audio_context_type) override {
    log::info("");
    getJniProfile().withCallbackEnv(__func__, [&](JNIEnv* env, jobject callbacks) {
      ScopedLocalRef<jbyteArray> addr(env, env->NewByteArray(sizeof(RawAddress)));
      if (!addr.get()) {
        log::error("Failed to new jbyteArray bd addr for connection state");
        return;
      }
      env->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress), (jbyte*)&address);

      env->CallVoidMethod(callbacks, method_onStreamMetadataUpdated, addr.get(), (jint)ase_id,
                          (jint)audio_context_type);
    });
  }

  void OnStreamStopped(const RawAddress& address, uint8_t ase_id) override {
    log::info("");
    getJniProfile().withCallbackEnv(__func__, [&](JNIEnv* env, jobject callbacks) {
      ScopedLocalRef<jbyteArray> addr(env, env->NewByteArray(sizeof(RawAddress)));
      if (!addr.get()) {
        log::error("Failed to new jbyteArray bd addr for connection state");
        return;
      }
      env->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress), (jbyte*)&address);

      env->CallVoidMethod(callbacks, method_onStreamStopped, addr.get(), (jint)ase_id);
    });
  }
};

static LeAudioServerCallbacksImpl sLeAudioServerCallbacks;

static JniProfile<LeAudioServerInterface, LeAudioServerCallbacks>& getJniProfile() {
  static JniProfile<LeAudioServerInterface, LeAudioServerCallbacks> sJniProfileInstance(
          &sLeAudioServerCallbacks);
  return sJniProfileInstance;
}

static void initNative(JNIEnv* env, jobject object) {
  log::info("");
  getJniProfile().init(env, object, sCallbacksField, BT_PROFILE_LE_AUDIO_PERIPHERAL_ID,
                       "LeAudioPeripheral");
  log::info("done");
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  log::info("");
  getJniProfile().cleanup(env, "LeAudioPeripheral");

  if (sStreamStartRequestInfoClass) {
    env->DeleteGlobalRef(sStreamStartRequestInfoClass);
    sStreamStartRequestInfoClass = nullptr;
  }
  if (sCodecIdClass) {
    env->DeleteGlobalRef(sCodecIdClass);
    sCodecIdClass = nullptr;
  }
  if (sAseDirectionClass) {
    env->DeleteGlobalRef(sAseDirectionClass);
    sAseDirectionClass = nullptr;
  }
}

static void confirmStreamStartRequestNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                            jboolean allowed) {
  log::info("");
  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  RawAddress* p_addr = (RawAddress*)addr;

  getJniProfile().withInterface([&](LeAudioServerInterface* iface) {
    iface->ConfirmStreamStartRequest(*p_addr, allowed);
  });

  env->ReleaseByteArrayElements(address, addr, 0);
}

static void stopStreamNative(JNIEnv* env, jobject /* object */, jbyteArray address, jint aseId) {
  log::info("");
  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  RawAddress* p_addr = (RawAddress*)addr;

  getJniProfile().withInterface(
          [&](LeAudioServerInterface* iface) { iface->StopStream(*p_addr, (uint8_t)aseId); });

  env->ReleaseByteArrayElements(address, addr, 0);
}

int register_com_android_bluetooth_le_audio_peripheral(JNIEnv* env) {
  log::info("");
  const JNINativeMethod methods[] = {
          {"initNative", "()V", (void*)initNative},
          {"cleanupNative", "()V", (void*)cleanupNative},
          {"confirmStreamStartRequestNative", "([BZ)V", (void*)confirmStreamStartRequestNative},
          {"stopStreamNative", "([BI)V", (void*)stopStreamNative},
  };
  const int result = REGISTER_NATIVE_METHODS(
          env, "com/android/bluetooth/le_audio/LeAudioPeripheralNativeInterface", methods);
  if (result != 0) {
    return result;
  }

  jclass jniLeAudioPeripheralNativeInterfaceClass =
          env->FindClass("com/android/bluetooth/le_audio/LeAudioPeripheralNativeInterface");
  sCallbacksField =
          env->GetFieldID(jniLeAudioPeripheralNativeInterfaceClass, "nativeCallback",
                          "Lcom/android/bluetooth/le_audio/LeAudioPeripheralNativeCallback;");
  env->DeleteLocalRef(jniLeAudioPeripheralNativeInterfaceClass);

  ScopedLocalRef<jclass> requestInfoClass(
          env, env->FindClass("com/android/bluetooth/le_audio/StreamStartRequestInfo"));
  if (!requestInfoClass.get()) {
    log::error("Could not find com/android/bluetooth/le_audio/StreamStartRequestInfo class");
    return JNI_ERR;
  }
  sStreamStartRequestInfoClass = (jclass)env->NewGlobalRef(requestInfoClass.get());
  sStreamStartRequestInfoConstructor = env->GetMethodID(
          sStreamStartRequestInfoClass, "<init>",
          "(ILcom/android/bluetooth/le_audio/StreamDirection;ILcom/android/bluetooth/le_audio/"
          "CodecId;I)V");

  ScopedLocalRef<jclass> codecIdClass(env,
                                      env->FindClass("com/android/bluetooth/le_audio/CodecId"));
  if (!codecIdClass.get()) {
    log::error(
            "Could not find "
            "com/android/bluetooth/le_audio/CodecId class");
    return JNI_ERR;
  }
  sCodecIdClass = (jclass)env->NewGlobalRef(codecIdClass.get());
  sCodecIdConstructor = env->GetMethodID(sCodecIdClass, "<init>", "(III)V");

  ScopedLocalRef<jclass> aseDirectionClass(
          env, env->FindClass("com/android/bluetooth/le_audio/StreamDirection"));
  if (!aseDirectionClass.get()) {
    log::error(
            "Could not find "
            "com/android/bluetooth/le_audio/StreamDirection class");
    return JNI_ERR;
  }
  sAseDirectionClass = (jclass)env->NewGlobalRef(aseDirectionClass.get());
  sAseDirectionFromMethod = env->GetStaticMethodID(
          sAseDirectionClass, "from", "(I)Lcom/android/bluetooth/le_audio/StreamDirection;");

  const JNIJavaMethod javaMethods[] = {
          {"onInitialized", "()V", &method_onInitialized},
          {"onConnectionStateChanged", "([BI)V", &method_onConnectionStateChanged},
          {"onStreamStartRequest", "([BLjava/util/List;)V", &method_onStreamStartRequest},
          {"onStreamStopped", "([BI)V", &method_onStreamStopped},
          {"onStreamStarted", "([BII)V", &method_onStreamStarted},
          {"onSinkStreamReady", "([B)V", &method_onSinkStreamReady},
          {"onSourceStreamReady", "([B)V", &method_onSourceStreamReady},
          {"onStreamMetadataUpdated", "([BII)V", &method_onStreamMetadataUpdated},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/le_audio/LeAudioPeripheralNativeCallback",
                   javaMethods);

  return 0;
}
}  // namespace android
