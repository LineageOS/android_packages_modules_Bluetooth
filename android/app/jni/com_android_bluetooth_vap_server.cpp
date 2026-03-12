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

 #define LOG_TAG "BluetoothVapServerJni"

 #include <bluetooth/log.h>
 #include <jni.h>
 #include <nativehelper/JNIHelp.h>
 #include <nativehelper/scoped_local_ref.h>

 #include <cerrno>
 #include <cstdint>
 #include <cstring>
 #include <mutex>
 #include <shared_mutex>
 #include <utility>
 #include <variant>
 #include <vector>

 #include "com_android_bluetooth.h"
 #include "hardware/bluetooth.h"
 #include "hardware/bt_vap_server.h"
 #include "bluetooth/types/address.h"

 using bluetooth::vap::VapServerCallbacks;
 using bluetooth::vap::VapServerInterface;

 namespace android {
 static jmethodID method_onInitialized;
 static jmethodID method_onStartVaSession;
 static jmethodID method_onStopVaSession;

 static VapServerInterface* sVapServerInterface = nullptr;
 static std::shared_timed_mutex interface_mutex;

 static jobject mCallbacksObj = nullptr;
 static std::shared_timed_mutex callbacks_mutex;
 static jfieldID sCallbacksField;

 class VapServerCallbacksImpl : public VapServerCallbacks {
 public:
   ~VapServerCallbacksImpl() = default;

   void OnInitialized(void) override {
     log::info("");
     std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
     CallbackEnv sCallbackEnv(__func__);
     if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
       return;
     }
     sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onInitialized);
   }

   void OnStartVaSession(const RawAddress& bd_addr) override {
     log::info(" received OnStartVaSession cb");

     std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
     CallbackEnv sCallbackEnv(__func__);
     if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
       return;
     }

     ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
     sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onStartVaSession, addr.get());
   }

   void OnStopVaSession(const RawAddress& bd_addr) override {
    log::info(" received OnStopVaSession cb");

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onStopVaSession, addr.get());
  }
 };

 static VapServerCallbacksImpl sVapServerCallbacks;

 static void initNative(JNIEnv* env, jobject obj) {
   std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
   std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

   const bt_interface_t* btInf = getBluetoothInterface();
   if (btInf == nullptr) {
     log::error("Bluetooth module is not loaded");
     return;
   }

   if (sVapServerInterface != nullptr) {
     log::info("Cleaning up VapServer Interface before initializing...");
     sVapServerInterface->Cleanup();
     sVapServerInterface = nullptr;
   }

   if (mCallbacksObj != nullptr) {
     log::info("Cleaning up VAP Server callback object");
     env->DeleteGlobalRef(mCallbacksObj);
     mCallbacksObj = nullptr;
   }

   if ((mCallbacksObj = env->NewGlobalRef(env->GetObjectField(obj, sCallbacksField))) == nullptr) {
     log::fatal("Failed to allocate Global Ref for VAP Server Callbacks");
   }

   sVapServerInterface =
       const_cast<VapServerInterface*>(reinterpret_cast<const VapServerInterface*>(
           btInf->get_profile_interface(BT_PROFILE_VAP_SERVER_ID)));
   if (sVapServerInterface == nullptr) {
     log::error("Failed to get Bluetooth VAP Server Interface");
     return;
   }

   sVapServerInterface->Init(&sVapServerCallbacks);
 }

 static void setCcidNative(JNIEnv* /*env*/, jobject /* object */, jint ccid) {
   std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);

   if (!sVapServerInterface) {
     log::error("Failed to get Bluetooth VAP Server Interface");
     return;
   }

   sVapServerInterface->SetCcid(ccid);
 }

 static void cleanupNative(JNIEnv* env, jobject /* object */) {
   std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
   std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

   const bt_interface_t* btInf = getBluetoothInterface();
   if (btInf == nullptr) {
     log::error("Bluetooth module is not loaded");
     return;
   }

   if (sVapServerInterface != nullptr) {
     sVapServerInterface->Cleanup();
     sVapServerInterface = nullptr;
   }

   if (mCallbacksObj != nullptr) {
     env->DeleteGlobalRef(mCallbacksObj);
     mCallbacksObj = nullptr;
   }
 }

 static void setVaNameNative(JNIEnv* env, jobject /* object */, jstring vaName) {
   std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);

   if (!sVapServerInterface) {
     log::error("Failed to get Bluetooth VAP Server Interface");
     return;
   }

   const char* va_name = nullptr;
   if (vaName) {
     va_name = env->GetStringUTFChars(vaName, nullptr);
   }

   // Assign a default value "None" if va_name is null (No VA engine selected)
   sVapServerInterface->SetVaName(va_name ? va_name : "None");

   if (va_name) {
     env->ReleaseStringUTFChars(vaName, va_name);
   }
 }

 int register_com_android_bluetooth_vap_server(JNIEnv* env) {
   const JNINativeMethod methods[] = {
           {"initNative", "()V", reinterpret_cast<void*>(initNative)},
           {"setCcidNative", "(I)V", reinterpret_cast<void*>(setCcidNative)},
           {"setVaNameNative", "(Ljava/lang/String;)V", reinterpret_cast<void*>(setVaNameNative)},
           {"cleanupNative", "()V", reinterpret_cast<void*>(cleanupNative)},
   };
   const char* jniNativeInterfaceClass = "com/android/bluetooth/vap/VapServerNativeInterface";
   const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
   if (result != 0) {
     return result;
   }

   sCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

   const JNIJavaMethod javaMethods[] = {
           {"onInitialized", "()V", &method_onInitialized},
           {"onStartVaSession", "([B)V", &method_onStartVaSession},
           {"onStopVaSession", "([B)V", &method_onStopVaSession},
   };
   GET_JAVA_METHODS(env, "com/android/bluetooth/vap/VapServerNativeCallback", javaMethods);

   return 0;
 }
 }  // namespace android
