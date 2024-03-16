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

#define LOG_TAG "BluetoothHidHostServiceJni"

#include "com_android_bluetooth.h"
#include "hardware/bt_hh.h"
#include "utils/Log.h"

#include <string.h>
#include <shared_mutex>
namespace android {

static jmethodID method_onConnectStateChanged;
static jmethodID method_onGetProtocolMode;
static jmethodID method_onGetReport;
static jmethodID method_onHandshake;
static jmethodID method_onVirtualUnplug;
static jmethodID method_onGetIdleTime;

static const bthh_interface_t* sBluetoothHidInterface = NULL;
static jobject mCallbacksObj = NULL;
static std::shared_timed_mutex mCallbacks_mutex;

static jbyteArray marshall_bda(RawAddress* bd_addr) {
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return NULL;

  jbyteArray addr = sCallbackEnv->NewByteArray(sizeof(RawAddress));
  if (!addr) {
    log::error("Fail to new jbyteArray bd addr");
    return NULL;
  }
  sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  return addr;
}

static void connection_state_callback(RawAddress* bd_addr,
                                      tBLE_ADDR_TYPE addr_type,
                                      tBT_TRANSPORT transport,
                                      bthh_connection_state_t state) {
  std::shared_lock<std::shared_timed_mutex> lock(mCallbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;
  if (!mCallbacksObj) {
    log::error("mCallbacksObj is null");
    return;
  }
  ScopedLocalRef<jbyteArray> addr(sCallbackEnv.get(), marshall_bda(bd_addr));
  if (!addr.get()) {
    log::error("Fail to new jbyteArray bd addr for HID channel state");
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectStateChanged,
                               addr.get(), (jint)addr_type, (jint)transport,
                               (jint)state);
}

static void get_protocol_mode_callback(RawAddress* bd_addr,
                                       tBLE_ADDR_TYPE addr_type,
                                       tBT_TRANSPORT transport,
                                       bthh_status_t hh_status,
                                       bthh_protocol_mode_t mode) {
  std::shared_lock<std::shared_timed_mutex> lock(mCallbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;
  if (!mCallbacksObj) {
    log::error("mCallbacksObj is null");
    return;
  }
  if (hh_status != BTHH_OK) {
    log::error("BTHH Status is not OK!");
    return;
  }

  ScopedLocalRef<jbyteArray> addr(sCallbackEnv.get(), marshall_bda(bd_addr));
  if (!addr.get()) {
    log::error("Fail to new jbyteArray bd addr for get protocol mode callback");
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGetProtocolMode,
                               addr.get(), (jint)addr_type, (jint)transport,
                               (jint)mode);
}

static void get_report_callback(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                                tBT_TRANSPORT transport,
                                bthh_status_t hh_status, uint8_t* rpt_data,
                                int rpt_size) {
  std::shared_lock<std::shared_timed_mutex> lock(mCallbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;
  if (!mCallbacksObj) {
    log::error("mCallbacksObj is null");
    return;
  }
  if (hh_status != BTHH_OK) {
    log::error("BTHH Status is not OK!");
    return;
  }

  ScopedLocalRef<jbyteArray> addr(sCallbackEnv.get(), marshall_bda(bd_addr));
  if (!addr.get()) {
    log::error("Fail to new jbyteArray bd addr for get report callback");
    return;
  }
  ScopedLocalRef<jbyteArray> data(sCallbackEnv.get(),
                                  sCallbackEnv->NewByteArray(rpt_size));
  if (!data.get()) {
    log::error("Fail to new jbyteArray data for get report callback");
    return;
  }

  sCallbackEnv->SetByteArrayRegion(data.get(), 0, rpt_size, (jbyte*)rpt_data);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGetReport, addr.get(),
                               (jint)addr_type, (jint)transport, data.get(),
                               (jint)rpt_size);
}

static void virtual_unplug_callback(RawAddress* bd_addr,
                                    tBLE_ADDR_TYPE addr_type,
                                    tBT_TRANSPORT transport,
                                    bthh_status_t hh_status) {
  log::verbose("call to virtual_unplug_callback");
  std::shared_lock<std::shared_timed_mutex> lock(mCallbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;
  if (!mCallbacksObj) {
    log::error("mCallbacksObj is null");
    return;
  }
  ScopedLocalRef<jbyteArray> addr(sCallbackEnv.get(), marshall_bda(bd_addr));
  if (!addr.get()) {
    log::error("Fail to new jbyteArray bd addr for HID channel state");
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onVirtualUnplug,
                               addr.get(), (jint)addr_type, (jint)transport,
                               (jint)hh_status);
}

static void handshake_callback(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                               tBT_TRANSPORT transport,
                               bthh_status_t hh_status) {
  std::shared_lock<std::shared_timed_mutex> lock(mCallbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;
  if (!mCallbacksObj) {
    log::error("mCallbacksObj is null");
    return;
  }

  ScopedLocalRef<jbyteArray> addr(sCallbackEnv.get(), marshall_bda(bd_addr));
  if (!addr.get()) {
    log::error("Fail to new jbyteArray bd addr for handshake callback");
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onHandshake, addr.get(),
                               (jint)addr_type, (jint)transport,
                               (jint)hh_status);
}

static void get_idle_time_callback(RawAddress* bd_addr,
                                   tBLE_ADDR_TYPE addr_type,
                                   tBT_TRANSPORT transport,
                                   bthh_status_t /* hh_status */,
                                   int idle_time) {
  std::shared_lock<std::shared_timed_mutex> lock(mCallbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(sCallbackEnv.get(), marshall_bda(bd_addr));
  if (!addr.get()) {
    log::error("Fail to new jbyteArray bd addr");
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGetIdleTime, addr.get(),
                               (jint)addr_type, (jint)transport,
                               (jint)idle_time);
}

static bthh_callbacks_t sBluetoothHidCallbacks = {
    sizeof(sBluetoothHidCallbacks),
    connection_state_callback,
    NULL,
    get_protocol_mode_callback,
    get_idle_time_callback,
    get_report_callback,
    virtual_unplug_callback,
    handshake_callback};

// Define native functions
static void initializeNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> lock(mCallbacks_mutex);
  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == NULL) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sBluetoothHidInterface != NULL) {
    log::warn("Cleaning up Bluetooth HID Interface before initializing...");
    sBluetoothHidInterface->cleanup();
    sBluetoothHidInterface = NULL;
  }

  if (mCallbacksObj != NULL) {
    log::warn("Cleaning up Bluetooth GID callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = NULL;
  }

  sBluetoothHidInterface =
      (bthh_interface_t*)btInf->get_profile_interface(BT_PROFILE_HIDHOST_ID);
  if (sBluetoothHidInterface == NULL) {
    log::error("Failed to get Bluetooth HID Interface");
    return;
  }

  bt_status_t status = sBluetoothHidInterface->init(&sBluetoothHidCallbacks);
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed to initialize Bluetooth HID, status: {}",
               bt_status_text(status));
    sBluetoothHidInterface = NULL;
    return;
  }

  mCallbacksObj = env->NewGlobalRef(object);
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> lock(mCallbacks_mutex);
  const bt_interface_t* btInf = getBluetoothInterface();

  if (btInf == NULL) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sBluetoothHidInterface != NULL) {
    log::warn("Cleaning up Bluetooth HID Interface...");
    sBluetoothHidInterface->cleanup();
    sBluetoothHidInterface = NULL;
  }

  if (mCallbacksObj != NULL) {
    log::warn("Cleaning up Bluetooth GID callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = NULL;
  }
}

static jboolean connectHidNative(JNIEnv* env, jobject /* object */,
                                 jbyteArray address, jint address_type,
                                 jint transport) {
  if (!sBluetoothHidInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }

  jboolean ret = JNI_TRUE;
  bt_status_t status = sBluetoothHidInterface->connect(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type,
      (tBT_TRANSPORT)transport);
  if (status != BT_STATUS_SUCCESS && status != BT_STATUS_BUSY) {
    log::error("Failed HID channel connection, status: {}",
               bt_status_text(status));
    ret = JNI_FALSE;
  }
  env->ReleaseByteArrayElements(address, addr, 0);

  return ret;
}

static jboolean disconnectHidNative(JNIEnv* env, jobject /* object */,
                                    jbyteArray address, jint address_type,
                                    jint transport,
                                    jboolean reconnect_allowed) {
  jbyte* addr;
  jboolean ret = JNI_TRUE;
  if (!sBluetoothHidInterface) return JNI_FALSE;

  addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }

  bt_status_t status = sBluetoothHidInterface->disconnect(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type, (tBT_TRANSPORT)transport,
      reconnect_allowed);
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed disconnect hid channel, status: {}",
               bt_status_text(status));
    ret = JNI_FALSE;
  }
  env->ReleaseByteArrayElements(address, addr, 0);

  return ret;
}

static jboolean getProtocolModeNative(JNIEnv* env, jobject /* object */,
                                      jbyteArray address, jint address_type,
                                      jint transport) {
  if (!sBluetoothHidInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }

  jboolean ret = JNI_TRUE;
  // TODO: protocolMode is unused by the backend: see b/28908173
  bthh_protocol_mode_t protocolMode = BTHH_UNSUPPORTED_MODE;
  bt_status_t status = sBluetoothHidInterface->get_protocol(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type, (tBT_TRANSPORT)transport,
      (bthh_protocol_mode_t)protocolMode);
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed get protocol mode, status: {}", bt_status_text(status));
    ret = JNI_FALSE;
  }
  env->ReleaseByteArrayElements(address, addr, 0);

  return ret;
}

static jboolean virtualUnPlugNative(JNIEnv* env, jobject /* object */,
                                    jbyteArray address, jint address_type,
                                    jint transport) {
  if (!sBluetoothHidInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }

  jboolean ret = JNI_TRUE;
  bt_status_t status = sBluetoothHidInterface->virtual_unplug(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type,
      (tBT_TRANSPORT)transport);
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed virual unplug, status: {}", bt_status_text(status));
    ret = JNI_FALSE;
  }
  env->ReleaseByteArrayElements(address, addr, 0);
  return ret;
}

static jboolean setProtocolModeNative(JNIEnv* env, jobject /* object */,
                                      jbyteArray address, jint address_type,
                                      jint transport, jint protocolMode) {
  if (!sBluetoothHidInterface) return JNI_FALSE;

  log::debug("protocolMode = {}", protocolMode);

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }

  bthh_protocol_mode_t mode;
  switch (protocolMode) {
    case 0:
      mode = BTHH_REPORT_MODE;
      break;
    case 1:
      mode = BTHH_BOOT_MODE;
      break;
    default:
      log::error("Unknown HID protocol mode");
      return JNI_FALSE;
  }

  jboolean ret = JNI_TRUE;
  bt_status_t status = sBluetoothHidInterface->set_protocol(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type, (tBT_TRANSPORT)transport,
      mode);
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed set protocol mode, status: {}", bt_status_text(status));
    ret = JNI_FALSE;
  }
  env->ReleaseByteArrayElements(address, addr, 0);

  return ret;
}

static jboolean getReportNative(JNIEnv* env, jobject /* object */,
                                jbyteArray address, jint address_type,
                                jint transport, jbyte reportType,
                                jbyte reportId, jint bufferSize) {
  log::verbose("reportType = {}, reportId = {}, bufferSize = {}", reportType,
               reportId, bufferSize);
  if (!sBluetoothHidInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }

  jint rType = reportType;
  jint rId = reportId;

  bt_status_t status = sBluetoothHidInterface->get_report(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type, (tBT_TRANSPORT)transport,
      (bthh_report_type_t)rType, (uint8_t)rId, bufferSize);
  jboolean ret = JNI_TRUE;
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed get report, status: {}", bt_status_text(status));
    ret = JNI_FALSE;
  }
  env->ReleaseByteArrayElements(address, addr, 0);

  return ret;
}

static jboolean setReportNative(JNIEnv* env, jobject /* object */,
                                jbyteArray address, jint address_type,
                                jint transport, jbyte reportType,
                                jstring report) {
  log::verbose("reportType = {}", reportType);
  if (!sBluetoothHidInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }
  jint rType = reportType;
  const char* c_report = env->GetStringUTFChars(report, NULL);

  jboolean ret = JNI_TRUE;
  bt_status_t status = sBluetoothHidInterface->set_report(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type, (tBT_TRANSPORT)transport,
      (bthh_report_type_t)rType, (char*)c_report);
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed set report, status: {}", bt_status_text(status));
    ret = JNI_FALSE;
  }
  env->ReleaseStringUTFChars(report, c_report);
  env->ReleaseByteArrayElements(address, addr, 0);

  return ret;
}

static jboolean sendDataNative(JNIEnv* env, jobject /* object */,
                               jbyteArray address, jint address_type,
                               jint transport, jstring report) {
  log::verbose("");
  jboolean ret = JNI_TRUE;
  if (!sBluetoothHidInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }

  const char* c_report = env->GetStringUTFChars(report, NULL);

  bt_status_t status = sBluetoothHidInterface->send_data(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type, (tBT_TRANSPORT)transport,
      (char*)c_report);
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed set data, status: {}", bt_status_text(status));
    ret = JNI_FALSE;
  }
  env->ReleaseStringUTFChars(report, c_report);
  env->ReleaseByteArrayElements(address, addr, 0);

  return ret;
}

static jboolean getIdleTimeNative(JNIEnv* env, jobject /* object */,
                                  jbyteArray address, jint address_type,
                                  jint transport) {
  if (!sBluetoothHidInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }

  bt_status_t status = sBluetoothHidInterface->get_idle_time(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type,
      (tBT_TRANSPORT)transport);
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed get idle time, status: {}", bt_status_text(status));
  }
  env->ReleaseByteArrayElements(address, addr, 0);

  return status == BT_STATUS_SUCCESS ? JNI_TRUE : JNI_FALSE;
}

static jboolean setIdleTimeNative(JNIEnv* env, jobject /* object */,
                                  jbyteArray address, jint address_type,
                                  jint transport, jbyte idle_time) {
  if (!sBluetoothHidInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    log::error("Bluetooth device address null");
    return JNI_FALSE;
  }

  bt_status_t status = sBluetoothHidInterface->set_idle_time(
      (RawAddress*)addr, (tBLE_ADDR_TYPE)address_type, (tBT_TRANSPORT)transport,
      idle_time);
  if (status != BT_STATUS_SUCCESS) {
    log::error("Failed set idle time, status: {}", bt_status_text(status));
  }
  env->ReleaseByteArrayElements(address, addr, 0);

  return status == BT_STATUS_SUCCESS ? JNI_TRUE : JNI_FALSE;
}

int register_com_android_bluetooth_hid_host(JNIEnv* env) {
  const JNINativeMethod methods[] = {
      {"initializeNative", "()V", (void*)initializeNative},
      {"cleanupNative", "()V", (void*)cleanupNative},
      {"connectHidNative", "([BII)Z", (void*)connectHidNative},
      {"disconnectHidNative", "([BIIZ)Z", (void*)disconnectHidNative},
      {"getProtocolModeNative", "([BII)Z", (void*)getProtocolModeNative},
      {"virtualUnPlugNative", "([BII)Z", (void*)virtualUnPlugNative},
      {"setProtocolModeNative", "([BIIB)Z", (void*)setProtocolModeNative},
      {"getReportNative", "([BIIBBI)Z", (void*)getReportNative},
      {"setReportNative", "([BIIBLjava/lang/String;)Z", (void*)setReportNative},
      {"sendDataNative", "([BIILjava/lang/String;)Z", (void*)sendDataNative},
      {"getIdleTimeNative", "([BII)Z", (void*)getIdleTimeNative},
      {"setIdleTimeNative", "([BIIB)Z", (void*)setIdleTimeNative},
  };
  const int result = REGISTER_NATIVE_METHODS(
      env, "com/android/bluetooth/hid/HidHostNativeInterface", methods);
  if (result != 0) {
    return result;
  }

  const JNIJavaMethod javaMethods[] = {
      {"onConnectStateChanged", "([BIII)V", &method_onConnectStateChanged},
      {"onGetProtocolMode", "([BIII)V", &method_onGetProtocolMode},
      {"onGetReport", "([BII[BI)V", &method_onGetReport},
      {"onHandshake", "([BIII)V", &method_onHandshake},
      {"onVirtualUnplug", "([BIII)V", &method_onVirtualUnplug},
      {"onGetIdleTime", "([BIII)V", &method_onGetIdleTime},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/hid/HidHostNativeInterface",
                   javaMethods);

  return 0;
}

}  // namespace android
