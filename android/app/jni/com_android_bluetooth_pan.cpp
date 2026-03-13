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

#define LOG_TAG "BluetoothPanServiceJni"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <jni.h>
#include <nativehelper/scoped_local_ref.h>

#include <cstring>

#include "bt_status.h"
#include "com_android_bluetooth.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_pan.h"

namespace android {

static jmethodID method_onConnectStateChanged;
static jmethodID method_onControlStateChanged;

static const btpan_interface_t* sPanIf = NULL;
static jobject mCallbacksObj = NULL;
static jfieldID sCallbacksField;

static void control_state_callback(btpan_control_state_t state, int local_role, BtStatus error,
                                   const char* ifname) {
  log::debug("state:{}, local_role:{}, ifname:{}", state, local_role, ifname);
  if (mCallbacksObj == NULL) {
    log::error("Callbacks Obj is NULL");
    return;
  }
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) {
    return;
  }
  ScopedLocalRef<jstring> js_ifname(sCallbackEnv.get(), sCallbackEnv->NewStringUTF(ifname));
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onControlStateChanged, (jint)local_role,
                               (jint)state, (jint)error, js_ifname.get());
}

static void connection_state_callback(btpan_connection_state_t state, BtStatus error,
                                      const RawAddress bd_addr, int local_role, int remote_role) {
  log::debug("state:{}, local_role:{}, remote_role:{}", state, local_role, remote_role);
  if (mCallbacksObj == NULL) {
    log::error("Callbacks Obj is NULL");
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) {
    return;
  }

  ScopedLocalRef<jbyteArray> jaddr = addressToJByteArray(sCallbackEnv, bd_addr);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectStateChanged, jaddr.get(),
                               (jint)state, (jint)error, (jint)local_role, (jint)remote_role);
}

static btpan_callbacks_t sBluetoothPanCallbacks = {
        sizeof(sBluetoothPanCallbacks), control_state_callback, connection_state_callback};

// Define native functions
static const bt_interface_t* btIf;

static void initializeNative(JNIEnv* env, jobject object) {
  log::debug("Initialize pan");
  if (btIf) {
    return;
  }

  btIf = getBluetoothInterface();
  if (btIf == NULL) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sPanIf != NULL) {
    log::warn("Cleaning up Bluetooth PAN Interface before initializing...");
    sPanIf->cleanup();
    sPanIf = NULL;
  }

  if (mCallbacksObj != NULL) {
    log::warn("Cleaning up Bluetooth PAN callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = NULL;
  }

  sPanIf = (btpan_interface_t*)btIf->get_profile_interface(BT_PROFILE_PAN_ID);
  if (sPanIf == NULL) {
    log::error("Failed to get Bluetooth PAN Interface");
    return;
  }

  if ((mCallbacksObj = env->NewGlobalRef(env->GetObjectField(object, sCallbacksField))) ==
      nullptr) {
    log::fatal("Failed to allocate Global Ref for Pan Callbacks");
  }

  BtStatus status = sPanIf->init(&sBluetoothPanCallbacks);
  if (!status) {
    log::error("Failed to initialize Bluetooth PAN, status: {}", status);
    sPanIf = NULL;
    if (mCallbacksObj != NULL) {
      log::warn("initialization failed: Cleaning up Bluetooth PAN callback object");
      env->DeleteGlobalRef(mCallbacksObj);
      mCallbacksObj = NULL;
    }
    return;
  }
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  log::debug("Cleanup pan");
  if (!btIf) {
    return;
  }

  if (sPanIf != NULL) {
    log::warn("Cleaning up Bluetooth PAN Interface...");
    sPanIf->cleanup();
    sPanIf = NULL;
  }

  if (mCallbacksObj != NULL) {
    log::warn("Cleaning up Bluetooth PAN callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = NULL;
  }
  btIf = NULL;
}

static jboolean connectPanNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                 jint src_role, jint dest_role) {
  log::debug("Connect pan");
  if (!sPanIf) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  jboolean ret = JNI_TRUE;

  BtStatus status = sPanIf->connect(bd_addr, src_role, dest_role);
  if (!status) {
    log::error("Failed PAN channel connection, status: {}", status);
    ret = JNI_FALSE;
  }

  return ret;
}

static jboolean disconnectPanNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::debug("Disconnects pan");
  if (!sPanIf) {
    return JNI_FALSE;
  }

  RawAddress bd_addr = addressFromJByteArray(env, address);
  jboolean ret = JNI_TRUE;

  BtStatus status = sPanIf->disconnect(bd_addr);
  if (!status) {
    log::error("Failed disconnect pan channel, status: {}", status);
    ret = JNI_FALSE;
  }

  return ret;
}

// JNI functions defined in PanNativeInterface
int register_com_android_bluetooth_pan(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initializeNative", "()V", (void*)initializeNative},
          {"cleanupNative", "()V", (void*)cleanupNative},
          {"connectPanNative", "([BII)Z", (void*)connectPanNative},
          {"disconnectPanNative", "([B)Z", (void*)disconnectPanNative},
  };
  const char* jniNativeInterfaceClass = "com/android/bluetooth/pan/PanNativeInterface";
  const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
  if (result != 0) {
    return result;
  }

  sCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

  // Client callback functions defined in PanNativeCallback
  const JNIJavaMethod javaMethods[]{
          {"onConnectStateChanged", "([BIIII)V", &method_onConnectStateChanged},
          {"onControlStateChanged", "(IIILjava/lang/String;)V", &method_onControlStateChanged},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/pan/PanNativeCallback", javaMethods);

  return 0;
}
}  // namespace android
