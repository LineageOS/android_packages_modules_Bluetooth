/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "BtGatt.JNI"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_local_ref.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <utility>
#include <vector>

#include "bt_status.h"
#include "btif_status.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_ras_api.h"
#include "com_android_bluetooth.h"
#include "com_android_bluetooth_flags.h"
#include "hardware/ble_advertiser.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_common_types.h"
#include "hardware/bt_gatt.h"
#include "hardware/bt_gatt_client.h"
#include "hardware/bt_gatt_server.h"
#include "hardware/bt_gatt_types.h"
#include "hardware/distance_measurement_interface.h"
#include "rust/cxx.h"
#include "src/gatt/ffi.rs.h"

using bluetooth::Uuid;

static RawAddress str2addr(JNIEnv* env, jstring address) {
  const char* c_address = env->GetStringUTFChars(address, NULL);
  if (!c_address) {
    return RawAddress::kEmpty;
  }

  RawAddress bd_addr = RawAddress::FromString(std::string(c_address)).value_or(RawAddress::kEmpty);
  env->ReleaseStringUTFChars(address, c_address);

  return bd_addr;
}

static std::vector<uint8_t> toVector(JNIEnv* env, jbyteArray ba) {
  jbyte* data_data = env->GetByteArrayElements(ba, NULL);
  uint16_t data_len = (uint16_t)env->GetArrayLength(ba);
  std::vector<uint8_t> data_vec(data_data, data_data + data_len);
  env->ReleaseByteArrayElements(ba, data_data, JNI_ABORT);
  return data_vec;
}

static std::string jstr_to_str(JNIEnv* env, jstring js) {
  const char* cstr = env->GetStringUTFChars(js, NULL);
  if (cstr == nullptr) {
    return "";
  }
  std::string ret = std::string(cstr);
  env->ReleaseStringUTFChars(js, cstr);
  return ret;
}

namespace android {

/**
 * Client callback methods
 */
static jmethodID method_onClientRegistered;
static jmethodID method_onConnected;
static jmethodID method_onDisconnected;
static jmethodID method_onReadCharacteristic;
static jmethodID method_onWriteCharacteristic;
static jmethodID method_onExecuteCompleted;
static jmethodID method_onReadDescriptor;
static jmethodID method_onWriteDescriptor;
static jmethodID method_onNotify;
static jmethodID method_onRegisterForNotifications;
static jmethodID method_onReadRemoteRssi;
static jmethodID method_onConfigureMTU;
static jmethodID method_onClientCongestion;

static jmethodID method_getSampleGattDbElement;
static jmethodID method_onGetGattDb;
static jmethodID method_onClientPhyUpdate;
static jmethodID method_onClientPhyRead;
static jmethodID method_onClientConnUpdate;
static jmethodID method_onServiceChanged;
static jmethodID method_onClientSubrateChange;
static jmethodID method_onClientCharacteristicsUnoffloaded;

/**
 * Server callback methods
 */
static jmethodID method_onServerRegistered;
static jmethodID method_onClientConnected;
static jmethodID method_onServiceAdded;
static jmethodID method_onServiceDeleted;
static jmethodID method_onResponseSendCompleted;
static jmethodID method_onServerReadCharacteristic;
static jmethodID method_onServerReadDescriptor;
static jmethodID method_onServerWriteCharacteristic;
static jmethodID method_onServerWriteDescriptor;
static jmethodID method_onExecuteWrite;
static jmethodID method_onNotificationSent;
static jmethodID method_onServerCongestion;
static jmethodID method_onServerMtuChanged;
static jmethodID method_onServerPhyUpdate;
static jmethodID method_onServerPhyRead;
static jmethodID method_onServerConnUpdate;
static jmethodID method_onServerSubrateChange;
static jmethodID method_onServerCharacteristicsUnoffloaded;

/**
 * Advertiser callback methods
 */
static jmethodID method_onAdvertisingSetStarted;
static jmethodID method_onOwnAddressRead;
static jmethodID method_onAdvertisingEnabled;
static jmethodID method_onAdvertisingDataSet;
static jmethodID method_onScanResponseDataSet;
static jmethodID method_onAdvertisingParametersUpdated;
static jmethodID method_onPeriodicAdvertisingParametersUpdated;
static jmethodID method_onPeriodicAdvertisingDataSet;
static jmethodID method_onPeriodicAdvertisingEnabled;

/**
 * Distance Measurement callback methods
 */
static jmethodID method_onDistanceMeasurementStarted;
static jmethodID method_onDistanceMeasurementStopped;
static jmethodID method_onDistanceMeasurementResult;

static struct {
  jclass clazz;
  jmethodID constructor;
} android_bluetooth_GattOffloadSession;

/**
 * Static variables
 */
static const btgatt_interface_t* sGattIf = NULL;

// Whilst sGattIf is initialized (with the callbacks we use below), sPrivateGattServerManager *must*
// be initialised because the callbacks make use of sPrivateGattServerManager.
static bluetooth::gatt::PrivateGattServerManager* sPrivateGattServerManager = NULL;

/** Pointer to the LE scanner interface methods.*/
static jobject mCallbacksObj = NULL;
static jfieldID sCallbacksField;
static jobject mAdvertiseCallbacksObj = NULL;
static jfieldID sAdvertiseCallbacksField;
static jobject mDistanceMeasurementCallbacksObj = NULL;
static jfieldID sDistanceMeasurementCallbacksField;
static std::shared_mutex callbacks_mutex;

/**
 * BTA client callbacks
 */

static void btgattc_register_app_cb(int status, int clientIf, const Uuid& app_uuid) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientRegistered, status, clientIf,
                               app_uuid.msb(), app_uuid.lsb());
}

static void btgattc_open_cb(int conn_id, int status, int clientIf, int transport,
                            const RawAddress& bda) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnected, clientIf, conn_id, transport,
                               status, address.get());
}

static void btgattc_close_cb(int conn_id, int status, int clientIf, int transport,
                             const RawAddress& bda) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onDisconnected, clientIf, conn_id, transport,
                               status, address.get());
}

static void btgattc_register_for_notification_cb(int conn_id, int registered, int status,
                                                 uint16_t handle) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onRegisterForNotifications, conn_id, status,
                               registered, handle);
}

static void btgattc_notify_cb(int conn_id, const btgatt_notify_params_t& p_data) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, p_data.bda);
  ScopedLocalRef<jbyteArray> jb(sCallbackEnv.get(), sCallbackEnv->NewByteArray(p_data.len));
  sCallbackEnv->SetByteArrayRegion(jb.get(), 0, p_data.len, (jbyte*)p_data.value);

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onNotify, conn_id, address.get(),
                               p_data.handle, p_data.is_notify, jb.get());
}

static void btgattc_read_characteristic_cb(int conn_id, int status,
                                           const btgatt_read_params_t& p_data) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jbyteArray> jb(sCallbackEnv.get(), NULL);
  if (status == 0) {  // Success
    jb.reset(sCallbackEnv->NewByteArray(p_data.value.len));
    sCallbackEnv->SetByteArrayRegion(jb.get(), 0, p_data.value.len, (jbyte*)p_data.value.value);
  } else {
    uint8_t value = 0;
    jb.reset(sCallbackEnv->NewByteArray(1));
    sCallbackEnv->SetByteArrayRegion(jb.get(), 0, 1, (jbyte*)&value);
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onReadCharacteristic, conn_id, status,
                               p_data.handle, jb.get());
}

static void btgattc_write_characteristic_cb(int conn_id, int status, uint16_t handle, uint16_t len,
                                            const uint8_t* value) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jbyteArray> jb(sCallbackEnv.get(), NULL);
  jb.reset(sCallbackEnv->NewByteArray(len));
  sCallbackEnv->SetByteArrayRegion(jb.get(), 0, len, (jbyte*)value);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onWriteCharacteristic, conn_id, status, handle,
                               jb.get());
}

static void btgattc_execute_write_cb(int conn_id, int status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExecuteCompleted, conn_id, status);
}

static void btgattc_read_descriptor_cb(int conn_id, int status,
                                       const btgatt_read_params_t& p_data) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jbyteArray> jb(sCallbackEnv.get(), NULL);
  if (p_data.value.len != 0) {
    jb.reset(sCallbackEnv->NewByteArray(p_data.value.len));
    sCallbackEnv->SetByteArrayRegion(jb.get(), 0, p_data.value.len, (jbyte*)p_data.value.value);
  } else {
    jb.reset(sCallbackEnv->NewByteArray(1));
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onReadDescriptor, conn_id, status,
                               p_data.handle, jb.get());
}

static void btgattc_write_descriptor_cb(int conn_id, int status, uint16_t handle, uint16_t len,
                                        const uint8_t* value) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jbyteArray> jb(sCallbackEnv.get(), NULL);
  jb.reset(sCallbackEnv->NewByteArray(len));
  sCallbackEnv->SetByteArrayRegion(jb.get(), 0, len, (jbyte*)value);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onWriteDescriptor, conn_id, status, handle,
                               jb.get());
}

static void btgattc_remote_rssi_cb(int client_if, const RawAddress& bda, int rssi, int status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onReadRemoteRssi, client_if, address.get(),
                               rssi, status);
}

static void btgattc_configure_mtu_cb(int conn_id, int status, int mtu) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConfigureMTU, conn_id, status, mtu);
}

static void btgattc_congestion_cb(int conn_id, bool congested) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientCongestion, conn_id, congested);
}

static void fillGattDbElementArray(JNIEnv* env, jobject* array, const btgatt_db_element_t* db,
                                   int count) {
  // Because JNI uses a different class loader in the callback context, we
  // cannot simply get the class.
  // As a workaround, we have to make sure we obtain an object of the class
  // first, as this will cause
  // class loader to load it.
  ScopedLocalRef<jobject> objectForClass(
          env, env->CallObjectMethod(mCallbacksObj, method_getSampleGattDbElement));
  ScopedLocalRef<jclass> gattDbElementClazz(env, env->GetObjectClass(objectForClass.get()));

  jmethodID gattDbElementConstructor = env->GetMethodID(gattDbElementClazz.get(), "<init>", "()V");

  jmethodID arrayAdd;

  const JNIJavaMethod javaMethods[] = {
          {"add", "(Ljava/lang/Object;)Z", &arrayAdd},
  };
  GET_JAVA_METHODS(env, "java/util/ArrayList", javaMethods);

  jmethodID uuidConstructor;

  const JNIJavaMethod javaUuidMethods[] = {
          {"<init>", "(JJ)V", &uuidConstructor},
  };
  GET_JAVA_METHODS(env, "java/util/UUID", javaUuidMethods);

  for (int i = 0; i < count; i++) {
    const btgatt_db_element_t& curr = db[i];

    ScopedLocalRef<jobject> element(
            env, env->NewObject(gattDbElementClazz.get(), gattDbElementConstructor));

    jfieldID fid = env->GetFieldID(gattDbElementClazz.get(), "id", "I");
    env->SetIntField(element.get(), fid, curr.id);

    fid = env->GetFieldID(gattDbElementClazz.get(), "attributeHandle", "I");
    env->SetIntField(element.get(), fid, curr.attribute_handle);

    ScopedLocalRef<jclass> uuidClazz(env, env->FindClass("java/util/UUID"));
    ScopedLocalRef<jobject> uuid(env, env->NewObject(uuidClazz.get(), uuidConstructor,
                                                     curr.uuid.msb(), curr.uuid.lsb()));
    fid = env->GetFieldID(gattDbElementClazz.get(), "uuid", "Ljava/util/UUID;");
    env->SetObjectField(element.get(), fid, uuid.get());

    fid = env->GetFieldID(gattDbElementClazz.get(), "type", "I");
    env->SetIntField(element.get(), fid, curr.type);

    fid = env->GetFieldID(gattDbElementClazz.get(), "attributeHandle", "I");
    env->SetIntField(element.get(), fid, curr.attribute_handle);

    fid = env->GetFieldID(gattDbElementClazz.get(), "startHandle", "I");
    env->SetIntField(element.get(), fid, curr.start_handle);

    fid = env->GetFieldID(gattDbElementClazz.get(), "endHandle", "I");
    env->SetIntField(element.get(), fid, curr.end_handle);

    fid = env->GetFieldID(gattDbElementClazz.get(), "properties", "I");
    env->SetIntField(element.get(), fid, curr.properties);

    env->CallBooleanMethod(*array, arrayAdd, element.get());
  }
}

static void btgattc_get_gatt_db_cb(int conn_id, const btgatt_db_element_t* db, int count) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  jclass arrayListclazz = sCallbackEnv->FindClass("java/util/ArrayList");
  ScopedLocalRef<jobject> array(
          sCallbackEnv.get(),
          sCallbackEnv->NewObject(arrayListclazz,
                                  sCallbackEnv->GetMethodID(arrayListclazz, "<init>", "()V")));

  jobject arrayPtr = array.get();
  fillGattDbElementArray(sCallbackEnv.get(), &arrayPtr, db, count);

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGetGattDb, conn_id, array.get());
}

static void btgattc_phy_updated_cb(int conn_id, uint8_t tx_phy, uint8_t rx_phy, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientPhyUpdate, conn_id, tx_phy, rx_phy,
                               status);
}

static void btgattc_conn_updated_cb(int conn_id, uint16_t interval, uint16_t latency,
                                    uint16_t timeout, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientConnUpdate, conn_id, interval, latency,
                               timeout, status);
}

static void btgattc_service_changed_cb(int conn_id) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServiceChanged, conn_id);
}

static void btgattc_subrate_change_cb(int conn_id, uint16_t subrate_factor, uint16_t latency,
                                      uint16_t cont_num, uint16_t timeout, uint8_t subrate_mode,
                                      uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientSubrateChange, conn_id, subrate_factor,
                               latency, cont_num, timeout, subrate_mode, status);
}

static void btgattc_characteristics_unoffloaded_cb(int conn_id, int session_id, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientCharacteristicsUnoffloaded, conn_id,
                               session_id, status);
}

static const btgatt_client_callbacks_t sGattClientCallbacks = {
        btgattc_register_app_cb,
        btgattc_open_cb,
        btgattc_close_cb,
        btgattc_register_for_notification_cb,
        btgattc_notify_cb,
        btgattc_read_characteristic_cb,
        btgattc_write_characteristic_cb,
        btgattc_read_descriptor_cb,
        btgattc_write_descriptor_cb,
        btgattc_execute_write_cb,
        btgattc_remote_rssi_cb,
        btgattc_configure_mtu_cb,
        btgattc_congestion_cb,
        btgattc_get_gatt_db_cb,
        NULL, /* services_removed_cb */
        NULL, /* services_added_cb */
        btgattc_phy_updated_cb,
        btgattc_conn_updated_cb,
        btgattc_service_changed_cb,
        btgattc_subrate_change_cb,
        btgattc_characteristics_unoffloaded_cb,
};

/**
 * BTA server callbacks
 */

static void btgatts_register_app_cb(int status, int server_if, const Uuid& uuid) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }
  sPrivateGattServerManager->OpenServer(server_if);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerRegistered, status, server_if,
                               uuid.msb(), uuid.lsb());
}

static void btgatts_connection_cb(int conn_id, int server_if, int transport, int connected,
                                  const RawAddress& bda) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientConnected, address.get(), transport,
                               connected, conn_id, server_if);
}

static void btgatts_service_added_cb(int status, int server_if, const btgatt_db_element_t* service,
                                     size_t service_count) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  // mirror the database in rust, now that it's created.
  if (status == 0x00 /* SUCCESS */) {
    auto service_records = rust::Vec<bluetooth::gatt::GattRecord>();
    for (size_t i = 0; i != service_count; ++i) {
      auto& curr_service = service[i];
      service_records.push_back(bluetooth::gatt::GattRecord{
              curr_service.uuid, (bluetooth::gatt::GattRecordType)curr_service.type,
              curr_service.attribute_handle, curr_service.properties,
              curr_service.extended_properties, curr_service.permissions});
    }
    sPrivateGattServerManager->AddService(server_if, std::move(service_records));
  }

  jclass arrayListclazz = sCallbackEnv->FindClass("java/util/ArrayList");
  ScopedLocalRef<jobject> array(
          sCallbackEnv.get(),
          sCallbackEnv->NewObject(arrayListclazz,
                                  sCallbackEnv->GetMethodID(arrayListclazz, "<init>", "()V")));
  jobject arrayPtr = array.get();
  fillGattDbElementArray(sCallbackEnv.get(), &arrayPtr, service, service_count);

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServiceAdded, status, server_if,
                               array.get());
}

static void btgatts_service_deleted_cb(int status, int server_if, int srvc_handle) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }
  sPrivateGattServerManager->RemoveService(server_if, srvc_handle);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServiceDeleted, status, server_if,
                               srvc_handle);
}

static void btgatts_request_read_characteristic_cb(int conn_id, int trans_id, const RawAddress& bda,
                                                   int attr_handle, int offset, bool is_long) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerReadCharacteristic, address.get(),
                               conn_id, trans_id, attr_handle, offset, is_long);
}

static void btgatts_request_read_descriptor_cb(int conn_id, int trans_id, const RawAddress& bda,
                                               int attr_handle, int offset, bool is_long) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerReadDescriptor, address.get(), conn_id,
                               trans_id, attr_handle, offset, is_long);
}

static void btgatts_request_write_characteristic_cb(int conn_id, int trans_id,
                                                    const RawAddress& bda, int attr_handle,
                                                    int offset, bool need_rsp, bool is_prep,
                                                    const uint8_t* value, size_t length) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);
  ScopedLocalRef<jbyteArray> val(sCallbackEnv.get(), sCallbackEnv->NewByteArray(length));
  if (val.get()) {
    sCallbackEnv->SetByteArrayRegion(val.get(), 0, length, (jbyte*)value);
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerWriteCharacteristic, address.get(),
                               conn_id, trans_id, attr_handle, offset, length, need_rsp, is_prep,
                               val.get());
}

static void btgatts_request_write_descriptor_cb(int conn_id, int trans_id, const RawAddress& bda,
                                                int attr_handle, int offset, bool need_rsp,
                                                bool is_prep, const uint8_t* value, size_t length) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);
  ScopedLocalRef<jbyteArray> val(sCallbackEnv.get(), sCallbackEnv->NewByteArray(length));
  if (val.get()) {
    sCallbackEnv->SetByteArrayRegion(val.get(), 0, length, (jbyte*)value);
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerWriteDescriptor, address.get(),
                               conn_id, trans_id, attr_handle, offset, length, need_rsp, is_prep,
                               val.get());
}

static void btgatts_request_exec_write_cb(int conn_id, int trans_id, const RawAddress& bda,
                                          int exec_write) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExecuteWrite, address.get(), conn_id,
                               trans_id, exec_write);
}

static void btgatts_response_confirmation_cb(int status, int handle) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onResponseSendCompleted, status, handle);
}

static void btgatts_indication_sent_cb(int conn_id, int status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onNotificationSent, conn_id, status);
}

static void btgatts_congestion_cb(int conn_id, bool congested) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerCongestion, conn_id, congested);
}

static void btgatts_mtu_changed_cb(int conn_id, int mtu) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerMtuChanged, conn_id, mtu);
}

static void btgatts_phy_updated_cb(int conn_id, uint8_t tx_phy, uint8_t rx_phy, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerPhyUpdate, conn_id, tx_phy, rx_phy,
                               status);
}

static void btgatts_conn_updated_cb(int conn_id, uint16_t interval, uint16_t latency,
                                    uint16_t timeout, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerConnUpdate, conn_id, interval, latency,
                               timeout, status);
}

static void btgatts_subrate_change_cb(int conn_id, uint16_t subrate_factor, uint16_t latency,
                                      uint16_t cont_num, uint16_t timeout, uint8_t subrate_mode,
                                      uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerSubrateChange, conn_id, subrate_factor,
                               latency, cont_num, timeout, subrate_mode, status);
}

static void btgatts_characteristics_unoffloaded_cb(int conn_id, int session_id, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerCharacteristicsUnoffloaded, conn_id,
                               session_id, status);
}

static const btgatt_server_callbacks_t sGattServerCallbacks = {
        btgatts_register_app_cb,
        btgatts_connection_cb,
        btgatts_service_added_cb,
        btgatts_service_deleted_cb,
        btgatts_request_read_characteristic_cb,
        btgatts_request_read_descriptor_cb,
        btgatts_request_write_characteristic_cb,
        btgatts_request_write_descriptor_cb,
        btgatts_request_exec_write_cb,
        btgatts_response_confirmation_cb,
        btgatts_indication_sent_cb,
        btgatts_congestion_cb,
        btgatts_mtu_changed_cb,
        btgatts_phy_updated_cb,
        btgatts_conn_updated_cb,
        btgatts_subrate_change_cb,
        btgatts_characteristics_unoffloaded_cb,
};

/**
 * GATT callbacks
 */

static const btgatt_callbacks_t sGattCallbacks = {
        sizeof(btgatt_callbacks_t),
        &sGattClientCallbacks,
        &sGattServerCallbacks,
};

class JniAdvertisingCallbacks : AdvertisingCallbacks {
public:
  static AdvertisingCallbacks* GetInstance() {
    static AdvertisingCallbacks* instance = new JniAdvertisingCallbacks();
    return instance;
  }

  void OnAdvertisingSetStarted(int reg_id, uint8_t advertiser_id, int8_t tx_power, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mAdvertiseCallbacksObj == NULL) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onAdvertisingSetStarted, reg_id,
                                 advertiser_id, tx_power, status);
  }

  void OnAdvertisingEnabled(uint8_t advertiser_id, bool enable, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mAdvertiseCallbacksObj == NULL) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onAdvertisingEnabled, advertiser_id,
                                 enable, status);
  }

  void OnAdvertisingDataSet(uint8_t advertiser_id, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mAdvertiseCallbacksObj == NULL) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onAdvertisingDataSet, advertiser_id,
                                 status);
  }

  void OnScanResponseDataSet(uint8_t advertiser_id, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mAdvertiseCallbacksObj == NULL) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onScanResponseDataSet,
                                 advertiser_id, status);
  }

  void OnAdvertisingParametersUpdated(uint8_t advertiser_id, int8_t tx_power, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mAdvertiseCallbacksObj == NULL) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onAdvertisingParametersUpdated,
                                 advertiser_id, tx_power, status);
  }

  void OnPeriodicAdvertisingParametersUpdated(uint8_t advertiser_id, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mAdvertiseCallbacksObj == NULL) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj,
                                 method_onPeriodicAdvertisingParametersUpdated, advertiser_id,
                                 status);
  }

  void OnPeriodicAdvertisingDataSet(uint8_t advertiser_id, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mAdvertiseCallbacksObj == NULL) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onPeriodicAdvertisingDataSet,
                                 advertiser_id, status);
  }

  void OnPeriodicAdvertisingEnabled(uint8_t advertiser_id, bool enable, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mAdvertiseCallbacksObj == NULL) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onPeriodicAdvertisingEnabled,
                                 advertiser_id, enable, status);
  }

  void OnOwnAddressRead(uint8_t advertiser_id, uint8_t address_type, RawAddress address) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mAdvertiseCallbacksObj == NULL) {
      return;
    }

    ScopedLocalRef<jstring> addr = addressToJString(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onOwnAddressRead, advertiser_id,
                                 address_type, addr.get());
  }
};

class JniDistanceMeasurementCallbacks : DistanceMeasurementCallbacks {
public:
  static DistanceMeasurementCallbacks* GetInstance() {
    static DistanceMeasurementCallbacks* instance = new JniDistanceMeasurementCallbacks();
    return instance;
  }

  void OnDistanceMeasurementStarted(RawAddress address, uint8_t method) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mDistanceMeasurementCallbacksObj) {
      return;
    }
    ScopedLocalRef<jstring> addr = addressToJString(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mDistanceMeasurementCallbacksObj,
                                 method_onDistanceMeasurementStarted, addr.get(), method);
  }

  void OnDistanceMeasurementStopped(RawAddress address, uint8_t reason, uint8_t method) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mDistanceMeasurementCallbacksObj) {
      return;
    }
    ScopedLocalRef<jstring> addr = addressToJString(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mDistanceMeasurementCallbacksObj,
                                 method_onDistanceMeasurementStopped, addr.get(), reason, method);
  }

  void OnDistanceMeasurementResult(RawAddress address, uint32_t centimeter,
                                   uint32_t error_centimeter, int azimuth_angle,
                                   int error_azimuth_angle, int altitude_angle,
                                   int error_altitude_angle, uint64_t elapsed_realtime_nanos,
                                   int remote_tx_power, int rssi, int8_t confidence_level,
                                   double delay_spread_meters, uint8_t detected_attack_level,
                                   double velocity_meters_per_second, uint8_t method) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mDistanceMeasurementCallbacksObj) {
      return;
    }
    ScopedLocalRef<jstring> addr = addressToJString(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(
            mDistanceMeasurementCallbacksObj, method_onDistanceMeasurementResult, addr.get(),
            centimeter, error_centimeter, azimuth_angle, error_azimuth_angle, altitude_angle,
            error_altitude_angle, elapsed_realtime_nanos, remote_tx_power, rssi, confidence_level,
            delay_spread_meters, detected_attack_level, velocity_meters_per_second, method);
  }
};

/**
 * Native function definitions
 */
static const bt_interface_t* btIf;

static void initializeNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);
  if (btIf) {
    return;
  }

  btIf = getBluetoothInterface();
  if (btIf == NULL) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sGattIf != NULL) {
    log::warn("Cleaning up Bluetooth GATT Interface before initializing...");
    sGattIf->cleanup();
    sGattIf = NULL;

    // Drop sPrivateGattServerManager
    rust::Box<bluetooth::gatt::PrivateGattServerManager>::from_raw(sPrivateGattServerManager);
    sPrivateGattServerManager = NULL;
  }

  if (mCallbacksObj != NULL) {
    log::warn("Cleaning up Bluetooth GATT callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = NULL;
  }

  android_bluetooth_GattOffloadSession.clazz = (jclass)env->NewGlobalRef(
          env->FindClass("android/bluetooth/GattOffloadSession$InnerParcel"));
  if (android_bluetooth_GattOffloadSession.clazz == nullptr) {
    log::error("Failed to allocate Global Ref for GattOffloadSession class");
    return;
  }
  android_bluetooth_GattOffloadSession.constructor =
          env->GetMethodID(android_bluetooth_GattOffloadSession.clazz, "<init>", "(II)V");

  sGattIf = (btgatt_interface_t*)btIf->get_profile_interface(BT_PROFILE_GATT_ID);
  if (sGattIf == NULL) {
    log::error("Failed to get Bluetooth GATT Interface");
    return;
  }

  BtStatus status = sGattIf->init(&sGattCallbacks);
  if (!status) {
    log::error("Failed to initialize Bluetooth GATT, status: {}", status);
    sGattIf = NULL;
    return;
  }

  // NewPrivateGattServerManager returns an object that we own, but it has a non-trivial destructor
  // so we cannot store it in a static variable.  It will be cleaned up in cleanupNative.
  sPrivateGattServerManager =
          bluetooth::gatt::NewPrivateGattServerManager(
                  std::make_unique<bluetooth::gatt::GattServerCallbacks>(sGattServerCallbacks),
                  bluetooth::shim::arbiter::GetArbiter())
                  .into_raw();

  sGattIf->advertiser->RegisterCallbacks(JniAdvertisingCallbacks::GetInstance());
  sGattIf->distance_measurement_manager->RegisterDistanceMeasurementCallbacks(
          JniDistanceMeasurementCallbacks::GetInstance());

  if ((mCallbacksObj = env->NewGlobalRef(env->GetObjectField(object, sCallbacksField))) ==
      nullptr) {
    log::fatal("Failed to allocate Global Ref for Gatt Callbacks");
  }
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);

  if (!btIf) {
    return;
  }

  if (sGattIf != NULL) {
    sGattIf->cleanup();
    sGattIf = NULL;

    // Drop sPrivateGattServerManager
    rust::Box<bluetooth::gatt::PrivateGattServerManager>::from_raw(sPrivateGattServerManager);
    sPrivateGattServerManager = NULL;
  }

  env->DeleteGlobalRef(android_bluetooth_GattOffloadSession.clazz);
  android_bluetooth_GattOffloadSession.clazz = nullptr;

  if (mCallbacksObj != NULL) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = NULL;
  }
  btIf = NULL;
}

/**
 * Native Client functions
 */

static int gattClientGetDeviceTypeNative(JNIEnv* env, jobject /* object */, jstring address) {
  if (!sGattIf) {
    return 0;
  }
  return sGattIf->client->get_device_type(str2addr(env, address));
}

static void gattClientRegisterAppNative(JNIEnv* env, jobject /* object */, jlong app_uuid_msb,
                                        jlong app_uuid_lsb, jstring name, jboolean eatt_support) {
  if (!sGattIf) {
    return;
  }
  Uuid uuid(app_uuid_msb, app_uuid_lsb);
  sGattIf->client->register_client(uuid, jstr_to_str(env, name).c_str(), eatt_support);
}

static void gattClientUnregisterAppNative(JNIEnv* /* env */, jobject /* object */, jint clientIf) {
  if (!sGattIf) {
    return;
  }
  sGattIf->client->unregister_client(clientIf);
}

static void gattClientConnectNative(JNIEnv* env, jobject /* object */, jint clientif,
                                    jstring address, jint addressType, jboolean isDirect,
                                    jint transport, jboolean opportunistic, jint preferred_mtu,
                                    jboolean prefer_relax_mode, jboolean auto_mtu_enabled) {
  if (!sGattIf) {
    return;
  }

  sGattIf->client->connect(clientif, str2addr(env, address), addressType, isDirect, transport,
                           opportunistic, preferred_mtu, prefer_relax_mode, auto_mtu_enabled);
}

static void gattClientDisconnectNative(JNIEnv* env, jobject /* object */, jint clientIf,
                                       jstring address, jint conn_id) {
  if (!sGattIf) {
    return;
  }
  sGattIf->client->disconnect(clientIf, str2addr(env, address), conn_id);
}

static void gattClientSetPreferredPhyNative(JNIEnv* env, jobject /* object */, jint /* clientIf */,
                                            jstring address, jint tx_phy, jint rx_phy,
                                            jint phy_options) {
  if (!sGattIf) {
    return;
  }
  sGattIf->client->set_preferred_phy(str2addr(env, address), tx_phy, rx_phy, phy_options);
}

static void readClientPhyCb(uint8_t clientIf, RawAddress bda, uint8_t tx_phy, uint8_t rx_phy,
                            uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientPhyRead, clientIf, address.get(),
                               tx_phy, rx_phy, status);
}

static void gattClientReadPhyNative(JNIEnv* env, jobject /* object */, jint clientIf,
                                    jstring address) {
  if (!sGattIf) {
    return;
  }

  RawAddress bda = str2addr(env, address);
  sGattIf->client->read_phy(bda, base::Bind(&readClientPhyCb, clientIf, bda));
}

static void gattClientRefreshNative(JNIEnv* env, jobject /* object */, jint clientIf,
                                    jstring address) {
  if (!sGattIf) {
    return;
  }

  sGattIf->client->refresh(clientIf, str2addr(env, address));
}

static void gattClientSearchServiceNative(JNIEnv* /* env */, jobject /* object */, jint conn_id,
                                          jboolean search_all, jlong service_uuid_msb,
                                          jlong service_uuid_lsb) {
  if (!sGattIf) {
    return;
  }

  Uuid uuid(service_uuid_msb, service_uuid_lsb);
  sGattIf->client->search_service(conn_id, search_all ? 0 : &uuid);
}

static void gattClientDiscoverServiceByUuidNative(JNIEnv* /* env */, jobject /* object */,
                                                  jint conn_id, jlong service_uuid_msb,
                                                  jlong service_uuid_lsb) {
  if (!sGattIf) {
    return;
  }

  Uuid uuid(service_uuid_msb, service_uuid_lsb);
  sGattIf->client->btif_gattc_discover_service_by_uuid(conn_id, uuid);
}

static void gattClientReadCharacteristicNative(JNIEnv* /* env */, jobject /* object */,
                                               jint conn_id, jint handle, jint authReq) {
  if (!sGattIf) {
    return;
  }

  sGattIf->client->read_characteristic(conn_id, handle, authReq);
}

static void gattClientReadUsingCharacteristicUuidNative(JNIEnv* /* env */, jobject /* object */,
                                                        jint conn_id, jlong uuid_msb,
                                                        jlong uuid_lsb, jint s_handle,
                                                        jint e_handle, jint authReq) {
  if (!sGattIf) {
    return;
  }

  Uuid uuid(uuid_msb, uuid_lsb);
  sGattIf->client->read_using_characteristic_uuid(conn_id, uuid, s_handle, e_handle, authReq);
}

static void gattClientReadDescriptorNative(JNIEnv* /* env */, jobject /* object */, jint conn_id,
                                           jint handle, jint authReq) {
  if (!sGattIf) {
    return;
  }

  sGattIf->client->read_descriptor(conn_id, handle, authReq);
}

static void gattClientWriteCharacteristicNative(JNIEnv* env, jobject /* object */, jint conn_id,
                                                jint handle, jint write_type, jint auth_req,
                                                jbyteArray value) {
  if (!sGattIf) {
    return;
  }

  if (value == NULL) {
    log::warn("gattClientWriteCharacteristicNative() ignoring NULL array");
    return;
  }

  uint16_t len = (uint16_t)env->GetArrayLength(value);
  jbyte* p_value = env->GetByteArrayElements(value, NULL);
  if (p_value == NULL) {
    return;
  }

  sGattIf->client->write_characteristic(conn_id, handle, write_type, auth_req,
                                        reinterpret_cast<uint8_t*>(p_value), len);

  env->ReleaseByteArrayElements(value, p_value, 0);
}

static void gattClientExecuteWriteNative(JNIEnv* /* env */, jobject /* object */, jint conn_id,
                                         jboolean execute) {
  if (!sGattIf) {
    return;
  }
  sGattIf->client->execute_write(conn_id, execute ? 1 : 0);
}

static void gattClientWriteDescriptorNative(JNIEnv* env, jobject /* object */, jint conn_id,
                                            jint handle, jint auth_req, jbyteArray value) {
  if (!sGattIf) {
    return;
  }

  if (value == NULL) {
    log::warn("gattClientWriteDescriptorNative() ignoring NULL array");
    return;
  }

  uint16_t len = (uint16_t)env->GetArrayLength(value);
  jbyte* p_value = env->GetByteArrayElements(value, NULL);
  if (p_value == NULL) {
    return;
  }

  sGattIf->client->write_descriptor(conn_id, handle, auth_req, reinterpret_cast<uint8_t*>(p_value),
                                    len);

  env->ReleaseByteArrayElements(value, p_value, 0);
}

static void gattClientRegisterForNotificationsNative(JNIEnv* env, jobject /* object */,
                                                     jint clientIf, jstring address, jint handle,
                                                     jboolean enable) {
  if (!sGattIf) {
    return;
  }

  RawAddress bd_addr = str2addr(env, address);
  if (enable) {
    sGattIf->client->register_for_notification(clientIf, bd_addr, handle);
  } else {
    sGattIf->client->deregister_for_notification(clientIf, bd_addr, handle);
  }
}

static void gattClientReadRemoteRssiNative(JNIEnv* env, jobject /* object */, jint clientif,
                                           jstring address) {
  if (!sGattIf) {
    return;
  }

  sGattIf->client->read_remote_rssi(clientif, str2addr(env, address));
}

static void gattClientConfigureMTUNative(JNIEnv* /* env */, jobject /* object */, jint conn_id,
                                         jint mtu) {
  if (!sGattIf) {
    return;
  }
  sGattIf->client->configure_mtu(conn_id, mtu);
}

static void gattConnectionParameterUpdateNative(JNIEnv* env, jobject /* object */,
                                                jint /* client_if */, jstring address,
                                                jint min_interval, jint max_interval, jint latency,
                                                jint timeout, jint min_ce_len, jint max_ce_len) {
  if (!sGattIf) {
    return;
  }
  sGattIf->client->conn_parameter_update(str2addr(env, address), min_interval, max_interval,
                                         latency, timeout, (uint16_t)min_ce_len,
                                         (uint16_t)max_ce_len);
}

static int gattSubrateRequestNative(JNIEnv* env, jobject /* object */, jint /* client_if */,
                                    jstring address, jint subrate_min, jint subrate_max,
                                    jint max_latency, jint cont_num, jint sup_timeout) {
  if (!sGattIf) {
    return 1;  // BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED
  }
  // TODO does BtStatus align with BluetoothStatusCodes ?
  if (com_android_bluetooth_flags_gatt_return_unsupported_when_not_support_subrating()) {
    BtStatus status = sGattIf->client->subrate_request(str2addr(env, address), subrate_min,
                                                        subrate_max, max_latency, cont_num,
                                                        sup_timeout);
    // BluetoothStatusCodes.FEATURE_NOT_SUPPORTED
    if (status.code() == UNSUPPORTED) return 11;
  } else {
    sGattIf->client->subrate_request(str2addr(env, address), subrate_min, subrate_max, max_latency,
                                    cont_num, sup_timeout);
  }
  return 0;  // BluetoothStatusCodes.SUCCESS
}

static int gattSubrateModeRequestNative(JNIEnv* env, jobject /* object */, jint client_if,
                                        jstring address, jint subrate_mode) {
  if (!sGattIf) {
    return 1;  // BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED
  }
  // TODO does bt_status_t align with BluetoothStatusCodes ?
  if (com_android_bluetooth_flags_gatt_return_unsupported_when_not_support_subrating()) {
    BtStatus status = sGattIf->client->subrate_mode_request(
        client_if, str2addr(env, address), subrate_mode);
    // BluetoothStatusCodes.FEATURE_NOT_SUPPORTED
    if (status.code() == UNSUPPORTED) return 11;
  } else {
    sGattIf->client->subrate_mode_request(client_if, str2addr(env, address), subrate_mode);
  }
  return 0;  // BluetoothStatusCodes.SUCCESS
}

/**
 * Native server functions
 */

static void gattServerRegisterAppNative(JNIEnv* /* env */, jobject /* object */, jlong app_uuid_msb,
                                        jlong app_uuid_lsb, jboolean eatt_support) {
  if (!sGattIf) {
    return;
  }
  Uuid uuid(app_uuid_msb, app_uuid_lsb);
  sGattIf->server->register_server(uuid, eatt_support);
}

static void gattServerUnregisterAppNative(JNIEnv* /* env */, jobject /* object */, jint serverIf) {
  if (!sGattIf) {
    return;
  }
  sPrivateGattServerManager->CloseServer(serverIf);
  sGattIf->server->unregister_server(serverIf);
}

static void gattServerConnectNative(JNIEnv* env, jobject /* object */, jint server_if,
                                    jstring address, jint addr_type, jboolean is_direct,
                                    jint transport) {
  if (!sGattIf) {
    return;
  }

  RawAddress bd_addr = str2addr(env, address);
  sGattIf->server->connect(server_if, bd_addr, addr_type, is_direct, transport);
}

static void gattServerDisconnectNative(JNIEnv* env, jobject /* object */, jint serverIf,
                                       jstring address, jint conn_id) {
  if (!sGattIf) {
    return;
  }
  sGattIf->server->disconnect(serverIf, str2addr(env, address), conn_id);
}

static void gattServerSetPreferredPhyNative(JNIEnv* env, jobject /* object */, jint /* serverIf */,
                                            jstring address, jint tx_phy, jint rx_phy,
                                            jint phy_options) {
  if (!sGattIf) {
    return;
  }
  RawAddress bda = str2addr(env, address);
  sGattIf->server->set_preferred_phy(bda, tx_phy, rx_phy, phy_options);
}

static void readServerPhyCb(uint8_t serverIf, RawAddress bda, uint8_t tx_phy, uint8_t rx_phy,
                            uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);

  sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerPhyRead, serverIf, address.get(),
                               tx_phy, rx_phy, status);
}

static void gattServerReadPhyNative(JNIEnv* env, jobject /* object */, jint serverIf,
                                    jstring address) {
  if (!sGattIf) {
    return;
  }

  RawAddress bda = str2addr(env, address);
  sGattIf->server->read_phy(bda, base::Bind(&readServerPhyCb, serverIf, bda));
}

static std::vector<btgatt_db_element_t> convertToDbElementsVector(JNIEnv* env,
                                                                  jobject gatt_db_elements) {
  jmethodID arrayGet;
  jmethodID arraySize;

  const JNIJavaMethod javaListMethods[] = {
          {"get", "(I)Ljava/lang/Object;", &arrayGet},
          {"size", "()I", &arraySize},
  };
  GET_JAVA_METHODS(env, "java/util/List", javaListMethods);

  int count = env->CallIntMethod(gatt_db_elements, arraySize);
  std::vector<btgatt_db_element_t> db;

  jmethodID uuidGetMsb;
  jmethodID uuidGetLsb;

  const JNIJavaMethod javaUuidMethods[] = {
          {"getMostSignificantBits", "()J", &uuidGetMsb},
          {"getLeastSignificantBits", "()J", &uuidGetLsb},
  };
  GET_JAVA_METHODS(env, "java/util/UUID", javaUuidMethods);

  jobject objectForClass = env->CallObjectMethod(mCallbacksObj, method_getSampleGattDbElement);
  jclass gattDbElementClazz = env->GetObjectClass(objectForClass);

  for (int i = 0; i < count; i++) {
    btgatt_db_element_t curr;

    jint index = i;
    ScopedLocalRef<jobject> element(env, env->CallObjectMethod(gatt_db_elements, arrayGet, index));

    jfieldID fid;

    fid = env->GetFieldID(gattDbElementClazz, "id", "I");
    curr.id = env->GetIntField(element.get(), fid);

    fid = env->GetFieldID(gattDbElementClazz, "uuid", "Ljava/util/UUID;");
    ScopedLocalRef<jobject> uuid(env, env->GetObjectField(element.get(), fid));
    if (uuid.get() != NULL) {
      jlong uuid_msb = env->CallLongMethod(uuid.get(), uuidGetMsb);
      jlong uuid_lsb = env->CallLongMethod(uuid.get(), uuidGetLsb);
      curr.uuid = Uuid(uuid_msb, uuid_lsb);
    }

    fid = env->GetFieldID(gattDbElementClazz, "type", "I");
    curr.type = (bt_gatt_db_attribute_type_t)env->GetIntField(element.get(), fid);

    fid = env->GetFieldID(gattDbElementClazz, "attributeHandle", "I");
    curr.attribute_handle = env->GetIntField(element.get(), fid);

    fid = env->GetFieldID(gattDbElementClazz, "startHandle", "I");
    curr.start_handle = env->GetIntField(element.get(), fid);

    fid = env->GetFieldID(gattDbElementClazz, "endHandle", "I");
    curr.end_handle = env->GetIntField(element.get(), fid);

    fid = env->GetFieldID(gattDbElementClazz, "properties", "I");
    curr.properties = env->GetIntField(element.get(), fid);

    fid = env->GetFieldID(gattDbElementClazz, "permissions", "I");
    curr.permissions = env->GetIntField(element.get(), fid);

    db.push_back(curr);
  }
  return db;
}

static void gattServerAddServiceNative(JNIEnv* env, jobject /* object */, jint server_if,
                                       jobject gatt_db_elements) {
  if (!sGattIf) {
    return;
  }

  std::vector<btgatt_db_element_t> db = convertToDbElementsVector(env, gatt_db_elements);
  sGattIf->server->add_service(server_if, db.data(), db.size());
}

static void gattServerDeleteServiceNative(JNIEnv* /* env */, jobject /* object */, jint server_if,
                                          jint svc_handle) {
  if (!sGattIf) {
    return;
  }
  sGattIf->server->delete_service(server_if, svc_handle);
}

static void gattServerSendIndicationNative(JNIEnv* env, jobject /* object */, jint server_if,
                                           jint attr_handle, jint conn_id, jbyteArray val) {
  if (!sGattIf) {
    return;
  }

  jbyte* array = env->GetByteArrayElements(val, 0);
  int val_len = env->GetArrayLength(val);

  if (sPrivateGattServerManager->IsConnectionIsolated(conn_id)) {
    auto data = ::rust::Slice<const uint8_t>((uint8_t*)array, val_len);
    sPrivateGattServerManager->SendIndication(server_if, attr_handle, conn_id, data);
  } else {
    sGattIf->server->send_indication(server_if, attr_handle, conn_id,
                                     /*confirm*/ 1, (uint8_t*)array, val_len);
  }

  env->ReleaseByteArrayElements(val, array, JNI_ABORT);
}

static void gattServerSendNotificationNative(JNIEnv* env, jobject /* object */, jint server_if,
                                             jint attr_handle, jint conn_id, jbyteArray val) {
  if (!sGattIf) {
    return;
  }

  jbyte* array = env->GetByteArrayElements(val, 0);
  int val_len = env->GetArrayLength(val);

  sGattIf->server->send_indication(server_if, attr_handle, conn_id,
                                   /*confirm*/ 0, (uint8_t*)array, val_len);

  env->ReleaseByteArrayElements(val, array, JNI_ABORT);
}

static void gattServerSendResponseNative(JNIEnv* env, jobject /* object */, jint server_if,
                                         jint conn_id, jint trans_id, jint status, jint handle,
                                         jint offset, jbyteArray val, jint auth_req) {
  if (!sGattIf) {
    return;
  }

  btgatt_response_t response;

  response.attr_value.handle = handle;
  response.attr_value.auth_req = auth_req;
  response.attr_value.offset = offset;
  response.attr_value.len = 0;

  if (val != NULL) {
    if (env->GetArrayLength(val) < GATT_MAX_ATTR_LEN) {
      response.attr_value.len = (uint16_t)env->GetArrayLength(val);
    } else {
      response.attr_value.len = GATT_MAX_ATTR_LEN;
    }

    jbyte* array = env->GetByteArrayElements(val, 0);

    for (int i = 0; i != response.attr_value.len; ++i) {
      response.attr_value.value[i] = (uint8_t)array[i];
    }
    env->ReleaseByteArrayElements(val, array, JNI_ABORT);
  }

  if (sPrivateGattServerManager->IsConnectionIsolated(conn_id)) {
    auto data = ::rust::Slice<const uint8_t>(response.attr_value.value, response.attr_value.len);
    sPrivateGattServerManager->SendResponse(server_if, conn_id, trans_id, status, data);
  } else {
    sGattIf->server->send_response(conn_id, trans_id, status, response);
  }
}

static void advertiseInitializeNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);
  if (mAdvertiseCallbacksObj != NULL) {
    log::warn("Cleaning up Advertise callback object");
    env->DeleteGlobalRef(mAdvertiseCallbacksObj);
    mAdvertiseCallbacksObj = NULL;
  }

  if ((mAdvertiseCallbacksObj = env->NewGlobalRef(
               env->GetObjectField(object, sAdvertiseCallbacksField))) == nullptr) {
    log::fatal("Failed to allocate Global Ref for Gatt Advertise Callbacks");
  }
}

static void advertiseCleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);
  if (mAdvertiseCallbacksObj != NULL) {
    env->DeleteGlobalRef(mAdvertiseCallbacksObj);
    mAdvertiseCallbacksObj = NULL;
  }
}

static uint32_t INTERVAL_MAX = 0xFFFFFF;
// Always give controller 31.25ms difference between min and max
static uint32_t INTERVAL_DELTA = 50;

static AdvertiseParameters parseParams(JNIEnv* env, jobject i) {
  AdvertiseParameters p;

  jclass clazz = env->GetObjectClass(i);
  jmethodID methodId;

  methodId = env->GetMethodID(clazz, "isConnectable", "()Z");
  jboolean isConnectable = env->CallBooleanMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "isDiscoverable", "()Z");
  jboolean isDiscoverable = env->CallBooleanMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "isScannable", "()Z");
  jboolean isScannable = env->CallBooleanMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "isLegacy", "()Z");
  jboolean isLegacy = env->CallBooleanMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "isAnonymous", "()Z");
  jboolean isAnonymous = env->CallBooleanMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "includeTxPower", "()Z");
  jboolean includeTxPower = env->CallBooleanMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "getPrimaryPhy", "()I");
  uint8_t primaryPhy = env->CallIntMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "getSecondaryPhy", "()I");
  uint8_t secondaryPhy = env->CallIntMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "getInterval", "()I");
  uint32_t interval = env->CallIntMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "getTxPowerLevel", "()I");
  int8_t txPowerLevel = env->CallIntMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "getOwnAddressType", "()I");
  int8_t ownAddressType = env->CallIntMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "isDirected", "()Z");
  jboolean isDirected = env->CallBooleanMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "isHighDutyCycle", "()Z");
  jboolean isHighDutyCycle = env->CallBooleanMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "getPeerAddress", "()Ljava/lang/String;");
  jstring peerAddress = (jstring)env->CallObjectMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "getPeerAddressType", "()I");
  int8_t peerAddressType = env->CallIntMethod(i, methodId);

  uint16_t props = 0;
  if (isConnectable) {
    props |= 0x01;
  }
  if (isScannable) {
    props |= 0x02;
  }
  if (isDirected) {
    props |= 0x04;
  }
  if (isHighDutyCycle) {
    props |= 0x08;
  }
  if (isLegacy) {
    props |= 0x10;
  }
  if (isAnonymous) {
    props |= 0x20;
  }
  if (includeTxPower) {
    props |= 0x40;
  }

  if (interval > INTERVAL_MAX - INTERVAL_DELTA) {
    interval = INTERVAL_MAX - INTERVAL_DELTA;
  }

  p.advertising_event_properties = props;
  p.min_interval = interval;
  p.max_interval = interval + INTERVAL_DELTA;
  p.channel_map = 0x07; /* all channels */
  p.tx_power = txPowerLevel;
  p.primary_advertising_phy = primaryPhy;
  p.secondary_advertising_phy = secondaryPhy;
  p.scan_request_notification_enable = false;
  p.own_address_type = ownAddressType;
  if (peerAddress == nullptr) {
    p.peer_address = RawAddress::kEmpty;
  } else {
    p.peer_address = str2addr(env, peerAddress);
  }
  p.peer_address_type = peerAddressType;
  p.discoverable = isDiscoverable;
  return p;
}

static PeriodicAdvertisingParameters parsePeriodicParams(JNIEnv* env, jobject i) {
  PeriodicAdvertisingParameters p;

  if (i == NULL) {
    p.enable = false;
    return p;
  }

  jclass clazz = env->GetObjectClass(i);
  jmethodID methodId;

  methodId = env->GetMethodID(clazz, "getIncludeTxPower", "()Z");
  jboolean includeTxPower = env->CallBooleanMethod(i, methodId);
  methodId = env->GetMethodID(clazz, "getInterval", "()I");
  uint16_t interval = env->CallIntMethod(i, methodId);

  p.enable = true;
  p.include_adi = true;
  p.min_interval = interval;
  p.max_interval = interval + 16; /* 20ms difference between min and max */
  uint16_t props = 0;
  if (includeTxPower) {
    props |= 0x40;
  }
  p.periodic_advertising_properties = props;
  return p;
}

static void ble_advertising_set_started_cb(int /*reg_id*/, int server_if, uint8_t advertiser_id,
                                           int8_t /*tx_power*/, uint8_t status) {
  // tie advertiser ID to server_if, once the advertisement has started
  if (status == 0 /* AdvertisingCallback::AdvertisingStatus::SUCCESS */ && server_if != 0) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    if (sPrivateGattServerManager) {
      sPrivateGattServerManager->AssociateServerWithAdvertiser(server_if, advertiser_id);
    }
  }
}

static void ble_advertising_set_timeout_cb(uint8_t advertiser_id, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mAdvertiseCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onAdvertisingEnabled, advertiser_id,
                               false, status);
}

static void startAdvertisingSetNative(JNIEnv* env, jobject /* object */, jobject parameters,
                                      jbyteArray adv_data, jbyteArray scan_resp,
                                      jobject periodic_parameters, jbyteArray periodic_data,
                                      jint duration, jint maxExtAdvEvents, jint reg_id,
                                      jint server_if) {
  if (!sGattIf) {
    return;
  }

  jbyte* scan_resp_data = env->GetByteArrayElements(scan_resp, NULL);
  uint16_t scan_resp_len = (uint16_t)env->GetArrayLength(scan_resp);
  std::vector<uint8_t> scan_resp_vec(scan_resp_data, scan_resp_data + scan_resp_len);
  env->ReleaseByteArrayElements(scan_resp, scan_resp_data, JNI_ABORT);

  AdvertiseParameters params = parseParams(env, parameters);
  PeriodicAdvertisingParameters periodicParams = parsePeriodicParams(env, periodic_parameters);

  jbyte* adv_data_data = env->GetByteArrayElements(adv_data, NULL);
  uint16_t adv_data_len = (uint16_t)env->GetArrayLength(adv_data);
  std::vector<uint8_t> data_vec(adv_data_data, adv_data_data + adv_data_len);
  env->ReleaseByteArrayElements(adv_data, adv_data_data, JNI_ABORT);

  jbyte* periodic_data_data = env->GetByteArrayElements(periodic_data, NULL);
  uint16_t periodic_data_len = (uint16_t)env->GetArrayLength(periodic_data);
  std::vector<uint8_t> periodic_data_vec(periodic_data_data,
                                         periodic_data_data + periodic_data_len);
  env->ReleaseByteArrayElements(periodic_data, periodic_data_data, JNI_ABORT);

  sGattIf->advertiser->StartAdvertisingSet(
          kAdvertiserClientIdJni, reg_id,
          base::Bind(&ble_advertising_set_started_cb, reg_id, server_if), params, data_vec,
          scan_resp_vec, periodicParams, periodic_data_vec, duration, maxExtAdvEvents,
          base::Bind(ble_advertising_set_timeout_cb));
}

static void stopAdvertisingSetNative(JNIEnv* /* env */, jobject /* object */, jint advertiser_id) {
  if (!sGattIf) {
    return;
  }

  sPrivateGattServerManager->ClearAdvertiser(advertiser_id);

  sGattIf->advertiser->Unregister(advertiser_id);
}

static void getOwnAddressCb(uint8_t advertiser_id, uint8_t address_type, RawAddress address) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mAdvertiseCallbacksObj) {
    return;
  }

  ScopedLocalRef<jstring> addr = addressToJString(sCallbackEnv, address);
  sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onOwnAddressRead, advertiser_id,
                               address_type, addr.get());
}

static void getOwnAddressNative(JNIEnv* /* env */, jobject /* object */, jint advertiser_id) {
  if (!sGattIf) {
    return;
  }
  sGattIf->advertiser->GetOwnAddress(advertiser_id, base::Bind(&getOwnAddressCb, advertiser_id));
}

static void callJniCallback(jmethodID method, uint8_t advertiser_id, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mAdvertiseCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method, advertiser_id, status);
}

static void enableSetCb(uint8_t advertiser_id, bool enable, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mAdvertiseCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onAdvertisingEnabled, advertiser_id,
                               enable, status);
}

static void enableAdvertisingSetNative(JNIEnv* /* env */, jobject /* object */, jint advertiser_id,
                                       jboolean enable, jint duration, jint maxExtAdvEvents) {
  if (!sGattIf) {
    return;
  }

  sGattIf->advertiser->Enable(advertiser_id, enable,
                              base::Bind(&enableSetCb, advertiser_id, enable), duration,
                              maxExtAdvEvents, base::Bind(&enableSetCb, advertiser_id, false));
}

static void setAdvertisingDataNative(JNIEnv* env, jobject /* object */, jint advertiser_id,
                                     jbyteArray data) {
  if (!sGattIf) {
    return;
  }

  sGattIf->advertiser->SetData(
          advertiser_id, false, toVector(env, data),
          base::Bind(&callJniCallback, method_onAdvertisingDataSet, advertiser_id));
}

static void setScanResponseDataNative(JNIEnv* env, jobject /* object */, jint advertiser_id,
                                      jbyteArray data) {
  if (!sGattIf) {
    return;
  }

  sGattIf->advertiser->SetData(
          advertiser_id, true, toVector(env, data),
          base::Bind(&callJniCallback, method_onScanResponseDataSet, advertiser_id));
}

static void setAdvertisingParametersNativeCb(uint8_t advertiser_id, uint8_t status,
                                             int8_t tx_power) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mAdvertiseCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onAdvertisingParametersUpdated,
                               advertiser_id, tx_power, status);
}

static void setAdvertisingParametersNative(JNIEnv* env, jobject /* object */, jint advertiser_id,
                                           jobject parameters) {
  if (!sGattIf) {
    return;
  }

  AdvertiseParameters params = parseParams(env, parameters);
  sGattIf->advertiser->SetParameters(advertiser_id, params,
                                     base::Bind(&setAdvertisingParametersNativeCb, advertiser_id));
}

static void setPeriodicAdvertisingParametersNative(JNIEnv* env, jobject /* object */,
                                                   jint advertiser_id,
                                                   jobject periodic_parameters) {
  if (!sGattIf) {
    return;
  }

  PeriodicAdvertisingParameters periodicParams = parsePeriodicParams(env, periodic_parameters);
  sGattIf->advertiser->SetPeriodicAdvertisingParameters(
          advertiser_id, periodicParams,
          base::Bind(&callJniCallback, method_onPeriodicAdvertisingParametersUpdated,
                     advertiser_id));
}

static void setPeriodicAdvertisingDataNative(JNIEnv* env, jobject /* object */, jint advertiser_id,
                                             jbyteArray data) {
  if (!sGattIf) {
    return;
  }

  sGattIf->advertiser->SetPeriodicAdvertisingData(
          advertiser_id, toVector(env, data),
          base::Bind(&callJniCallback, method_onPeriodicAdvertisingDataSet, advertiser_id));
}

static void enablePeriodicSetCb(uint8_t advertiser_id, bool enable, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mAdvertiseCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mAdvertiseCallbacksObj, method_onPeriodicAdvertisingEnabled,
                               advertiser_id, enable, status);
}

static void setPeriodicAdvertisingEnableNative(JNIEnv* /* env */, jobject /* object */,
                                               jint advertiser_id, jboolean enable) {
  if (!sGattIf) {
    return;
  }

  sGattIf->advertiser->SetPeriodicAdvertisingEnable(
          advertiser_id, enable, true /*include_adi*/,
          base::Bind(&enablePeriodicSetCb, advertiser_id, enable));
}

static jobject gattClientOffloadCharacteristicsNative(JNIEnv* env, jobject /* object */,
                                                      jint conn_id, jobject gatt_db_elements,
                                                      jlong endpoint_Id, jlong hub_id, jint uid,
                                                      jstring attribution_tag) {
  if (!sGattIf) {
    return env->NewObject(android_bluetooth_GattOffloadSession.clazz,
                          android_bluetooth_GattOffloadSession.constructor,
                          BTGATT_OFFLOAD_SESSION_ID_UNKNOWN, tGATT_STATUS::GATT_ERROR);
  }

  std::string attribution_tag_str = stringFromJstring(env, attribution_tag);
  btgatt_offload_result_t result{BTGATT_OFFLOAD_SESSION_ID_UNKNOWN, tGATT_STATUS::GATT_ERROR};
  std::vector<btgatt_db_element_t> db = convertToDbElementsVector(env, gatt_db_elements);
  sGattIf->client->offload_characteristics(conn_id, db.data(), db.size(), endpoint_Id, hub_id, uid,
                                           std::move(attribution_tag_str), &result);
  return env->NewObject(android_bluetooth_GattOffloadSession.clazz,
                        android_bluetooth_GattOffloadSession.constructor, result.session_id,
                        result.status);
}

static jobject gattServerOffloadCharacteristicsNative(JNIEnv* env, jobject /* object */,
                                                      jint conn_id, jobject gatt_db_elements,
                                                      jlong endpoint_Id, jlong hub_id, jint uid,
                                                      jstring attribution_tag) {
  if (!sGattIf) {
    return env->NewObject(android_bluetooth_GattOffloadSession.clazz,
                          android_bluetooth_GattOffloadSession.constructor,
                          BTGATT_OFFLOAD_SESSION_ID_UNKNOWN, tGATT_STATUS::GATT_ERROR);
  }
  std::string attribution_tag_str = stringFromJstring(env, attribution_tag);
  btgatt_offload_result_t result{BTGATT_OFFLOAD_SESSION_ID_UNKNOWN, tGATT_STATUS::GATT_ERROR};
  std::vector<btgatt_db_element_t> db = convertToDbElementsVector(env, gatt_db_elements);
  sGattIf->server->offload_characteristics(conn_id, db.data(), db.size(), endpoint_Id, hub_id, uid,
                                           std::move(attribution_tag_str), &result);
  return env->NewObject(android_bluetooth_GattOffloadSession.clazz,
                        android_bluetooth_GattOffloadSession.constructor, result.session_id,
                        result.status);
}

static void gattClientUnoffloadCharacteristicsNative(JNIEnv* /* env */, jobject /* object */,
                                                     jint conn_id, jint session_id) {
  if (!sGattIf) {
    return;
  }

  sGattIf->client->unoffload_characteristics(conn_id, session_id);
}

static void gattServerUnoffloadCharacteristicsNative(JNIEnv* /* env */, jobject /* object */,
                                                     jint conn_id, jint session_id) {
  if (!sGattIf) {
    return;
  }

  sGattIf->server->unoffload_characteristics(conn_id, session_id);
}

static void distanceMeasurementInitializeNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);
  if (mDistanceMeasurementCallbacksObj != NULL) {
    log::warn("Cleaning up Advertise callback object");
    env->DeleteGlobalRef(mDistanceMeasurementCallbacksObj);
    mDistanceMeasurementCallbacksObj = NULL;
  }

  if ((mDistanceMeasurementCallbacksObj = env->NewGlobalRef(
               env->GetObjectField(object, sDistanceMeasurementCallbacksField))) == nullptr) {
    log::fatal("Failed to allocate Global Ref for Gatt Distance Measurement Callbacks");
  }
}

static void distanceMeasurementCleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);
  if (mDistanceMeasurementCallbacksObj != NULL) {
    env->DeleteGlobalRef(mDistanceMeasurementCallbacksObj);
    mDistanceMeasurementCallbacksObj = NULL;
  }
}

static void startDistanceMeasurementNative(JNIEnv* env, jobject /* object */, jint appUid,
                                           jstring address, jint interval, jint method,
                                           jint sight_type, jint location_type) {
  if (!sGattIf) {
    return;
  }
  sGattIf->distance_measurement_manager->StartDistanceMeasurement(
          appUid, str2addr(env, address), interval, method, sight_type, location_type);
}

static void stopDistanceMeasurementNative(JNIEnv* env, jobject /* object */, jstring address,
                                          jint method) {
  if (!sGattIf) {
    return;
  }
  sGattIf->distance_measurement_manager->StopDistanceMeasurement(str2addr(env, address), method);
}

// JNI functions defined in AdvertiseManagerNativeInterface
static int register_com_android_bluetooth_gatt_advertise_manager(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initializeNative", "()V", (void*)advertiseInitializeNative},
          {"cleanupNative", "()V", (void*)advertiseCleanupNative},
          {"startAdvertisingSetNative",
           "(Landroid/bluetooth/le/AdvertisingSetParameters;"
           "[B[BLandroid/bluetooth/le/PeriodicAdvertisingParameters;[BIIII)V",
           (void*)startAdvertisingSetNative},
          {"stopAdvertisingSetNative", "(I)V", (void*)stopAdvertisingSetNative},
          {"getOwnAddressNative", "(I)V", (void*)getOwnAddressNative},
          {"enableAdvertisingSetNative", "(IZII)V", (void*)enableAdvertisingSetNative},
          {"setAdvertisingDataNative", "(I[B)V", (void*)setAdvertisingDataNative},
          {"setScanResponseDataNative", "(I[B)V", (void*)setScanResponseDataNative},
          {"setAdvertisingParametersNative", "(ILandroid/bluetooth/le/AdvertisingSetParameters;)V",
           (void*)setAdvertisingParametersNative},
          {"setPeriodicAdvertisingParametersNative",
           "(ILandroid/bluetooth/le/PeriodicAdvertisingParameters;)V",
           (void*)setPeriodicAdvertisingParametersNative},
          {"setPeriodicAdvertisingDataNative", "(I[B)V", (void*)setPeriodicAdvertisingDataNative},
          {"setPeriodicAdvertisingEnableNative", "(IZ)V",
           (void*)setPeriodicAdvertisingEnableNative},
  };
  const char* jniNativeInterfaceClass =
          "com/android/bluetooth/gatt/AdvertiseManagerNativeInterface";
  const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
  if (result != 0) {
    return result;
  }

  sAdvertiseCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

  // Client callback functions defined in AdvertiseManagerNativeCallback
  const JNIJavaMethod javaMethods[] = {
          {"onAdvertisingSetStarted", "(IIII)V", &method_onAdvertisingSetStarted},
          {"onOwnAddressRead", "(IILjava/lang/String;)V", &method_onOwnAddressRead},
          {"onAdvertisingEnabled", "(IZI)V", &method_onAdvertisingEnabled},
          {"onAdvertisingDataSet", "(II)V", &method_onAdvertisingDataSet},
          {"onScanResponseDataSet", "(II)V", &method_onScanResponseDataSet},
          {"onAdvertisingParametersUpdated", "(III)V", &method_onAdvertisingParametersUpdated},
          {"onPeriodicAdvertisingParametersUpdated", "(II)V",
           &method_onPeriodicAdvertisingParametersUpdated},
          {"onPeriodicAdvertisingDataSet", "(II)V", &method_onPeriodicAdvertisingDataSet},
          {"onPeriodicAdvertisingEnabled", "(IZI)V", &method_onPeriodicAdvertisingEnabled},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/gatt/AdvertiseManagerNativeCallback", javaMethods);
  return 0;
}

// JNI functions defined in DistanceMeasurementNativeInterface
static int register_com_android_bluetooth_gatt_distance_measurement(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initializeNative", "()V", (void*)distanceMeasurementInitializeNative},
          {"cleanupNative", "()V", (void*)distanceMeasurementCleanupNative},
          {"startDistanceMeasurementNative", "(ILjava/lang/String;IIII)V",
           (void*)startDistanceMeasurementNative},
          {"stopDistanceMeasurementNative", "(Ljava/lang/String;I)V",
           (void*)stopDistanceMeasurementNative},
  };
  const int result = REGISTER_NATIVE_METHODS(
          env, "com/android/bluetooth/gatt/DistanceMeasurementNativeInterface", methods);
  if (result != 0) {
    return result;
  }

  sDistanceMeasurementCallbacksField = getNativeCallbackField(
          env, "com/android/bluetooth/gatt/DistanceMeasurementNativeInterface");

  // Client callback functions defined in DistanceMeasurementNativeCallback
  const JNIJavaMethod javaMethods[] = {
          {"onDistanceMeasurementStarted", "(Ljava/lang/String;I)V",
           &method_onDistanceMeasurementStarted},
          {"onDistanceMeasurementStopped", "(Ljava/lang/String;II)V",
           &method_onDistanceMeasurementStopped},
          {"onDistanceMeasurementResult", "(Ljava/lang/String;IIIIIIJIIIDIDI)V",
           &method_onDistanceMeasurementResult},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/gatt/DistanceMeasurementNativeCallback",
                   javaMethods);
  return 0;
}

// JNI functions defined in GattNativeInterface
static int register_com_android_bluetooth_gatt_(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initializeNative", "()V", (void*)initializeNative},
          {"cleanupNative", "()V", (void*)cleanupNative},
          {"gattClientGetDeviceTypeNative", "(Ljava/lang/String;)I",
           (void*)gattClientGetDeviceTypeNative},
          {"gattClientRegisterAppNative", "(JJLjava/lang/String;Z)V",
           (void*)gattClientRegisterAppNative},
          {"gattClientUnregisterAppNative", "(I)V", (void*)gattClientUnregisterAppNative},
          {"gattClientConnectNative", "(ILjava/lang/String;IZIZIZZ)V",
           (void*)gattClientConnectNative},
          {"gattClientDisconnectNative", "(ILjava/lang/String;I)V",
           (void*)gattClientDisconnectNative},
          {"gattClientSetPreferredPhyNative", "(ILjava/lang/String;III)V",
           (void*)gattClientSetPreferredPhyNative},
          {"gattClientReadPhyNative", "(ILjava/lang/String;)V", (void*)gattClientReadPhyNative},
          {"gattClientRefreshNative", "(ILjava/lang/String;)V", (void*)gattClientRefreshNative},
          {"gattClientSearchServiceNative", "(IZJJ)V", (void*)gattClientSearchServiceNative},
          {"gattClientDiscoverServiceByUuidNative", "(IJJ)V",
           (void*)gattClientDiscoverServiceByUuidNative},
          {"gattClientReadCharacteristicNative", "(III)V",
           (void*)gattClientReadCharacteristicNative},
          {"gattClientReadUsingCharacteristicUuidNative", "(IJJIII)V",
           (void*)gattClientReadUsingCharacteristicUuidNative},
          {"gattClientReadDescriptorNative", "(III)V", (void*)gattClientReadDescriptorNative},
          {"gattClientWriteCharacteristicNative", "(IIII[B)V",
           (void*)gattClientWriteCharacteristicNative},
          {"gattClientWriteDescriptorNative", "(III[B)V", (void*)gattClientWriteDescriptorNative},
          {"gattClientExecuteWriteNative", "(IZ)V", (void*)gattClientExecuteWriteNative},
          {"gattClientRegisterForNotificationsNative", "(ILjava/lang/String;IZ)V",
           (void*)gattClientRegisterForNotificationsNative},
          {"gattClientReadRemoteRssiNative", "(ILjava/lang/String;)V",
           (void*)gattClientReadRemoteRssiNative},
          {"gattClientConfigureMTUNative", "(II)V", (void*)gattClientConfigureMTUNative},
          {"gattConnectionParameterUpdateNative", "(ILjava/lang/String;IIIIII)V",
           (void*)gattConnectionParameterUpdateNative},
          {"gattServerRegisterAppNative", "(JJZ)V", (void*)gattServerRegisterAppNative},
          {"gattServerUnregisterAppNative", "(I)V", (void*)gattServerUnregisterAppNative},
          {"gattServerConnectNative", "(ILjava/lang/String;IZI)V", (void*)gattServerConnectNative},
          {"gattServerDisconnectNative", "(ILjava/lang/String;I)V",
           (void*)gattServerDisconnectNative},
          {"gattServerSetPreferredPhyNative", "(ILjava/lang/String;III)V",
           (void*)gattServerSetPreferredPhyNative},
          {"gattServerReadPhyNative", "(ILjava/lang/String;)V", (void*)gattServerReadPhyNative},
          {"gattServerAddServiceNative", "(ILjava/util/List;)V", (void*)gattServerAddServiceNative},
          {"gattServerDeleteServiceNative", "(II)V", (void*)gattServerDeleteServiceNative},
          {"gattServerSendIndicationNative", "(III[B)V", (void*)gattServerSendIndicationNative},
          {"gattServerSendNotificationNative", "(III[B)V", (void*)gattServerSendNotificationNative},
          {"gattServerSendResponseNative", "(IIIIII[BI)V", (void*)gattServerSendResponseNative},
          {"gattSubrateRequestNative", "(ILjava/lang/String;IIIII)I",
           (void*)gattSubrateRequestNative},
          {"gattClientOffloadCharacteristicsNative",
           "(ILjava/util/List;JJILjava/lang/String;)Landroid/bluetooth/"
           "GattOffloadSession$InnerParcel;",
           (void*)gattClientOffloadCharacteristicsNative},
          {"gattServerOffloadCharacteristicsNative",
           "(ILjava/util/List;JJILjava/lang/String;)Landroid/bluetooth/"
           "GattOffloadSession$InnerParcel;",
           (void*)gattServerOffloadCharacteristicsNative},
          {"gattClientUnoffloadCharacteristicsNative", "(II)V",
           (void*)gattClientUnoffloadCharacteristicsNative},
          {"gattServerUnoffloadCharacteristicsNative", "(II)V",
           (void*)gattServerUnoffloadCharacteristicsNative},
          {"gattSubrateModeRequestNative", "(ILjava/lang/String;I)I",
           (void*)gattSubrateModeRequestNative},
  };
  const char* jniNativeInterfaceClass = "com/android/bluetooth/gatt/GattNativeInterface";
  const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
  if (result != 0) {
    return result;
  }

  sCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

  // Client callback functions defined in GattNativeCallback
  const JNIJavaMethod javaMethods[] = {
          // Client callbacks
          {"onClientRegistered", "(IIJJ)V", &method_onClientRegistered},
          {"onConnected", "(IIIILjava/lang/String;)V", &method_onConnected},
          {"onDisconnected", "(IIIILjava/lang/String;)V", &method_onDisconnected},
          {"onReadCharacteristic", "(III[B)V", &method_onReadCharacteristic},
          {"onWriteCharacteristic", "(III[B)V", &method_onWriteCharacteristic},
          {"onExecuteCompleted", "(II)V", &method_onExecuteCompleted},
          {"onReadDescriptor", "(III[B)V", &method_onReadDescriptor},
          {"onWriteDescriptor", "(III[B)V", &method_onWriteDescriptor},
          {"onNotify", "(ILjava/lang/String;IZ[B)V", &method_onNotify},
          {"onRegisterForNotifications", "(IIII)V", &method_onRegisterForNotifications},
          {"onReadRemoteRssi", "(ILjava/lang/String;II)V", &method_onReadRemoteRssi},
          {"onConfigureMTU", "(III)V", &method_onConfigureMTU},
          {"onClientCongestion", "(IZ)V", &method_onClientCongestion},
          {"getSampleGattDbElement", "()Lcom/android/bluetooth/gatt/GattDbElement;",
           &method_getSampleGattDbElement},
          {"onGetGattDb", "(ILjava/util/List;)V", &method_onGetGattDb},
          {"onClientPhyRead", "(ILjava/lang/String;III)V", &method_onClientPhyRead},
          {"onClientPhyUpdate", "(IIII)V", &method_onClientPhyUpdate},
          {"onClientConnUpdate", "(IIIII)V", &method_onClientConnUpdate},
          {"onServiceChanged", "(I)V", &method_onServiceChanged},
          {"onClientSubrateChange", "(IIIIIII)V", &method_onClientSubrateChange},
          {"onClientCharacteristicsUnoffloaded", "(III)V",
           &method_onClientCharacteristicsUnoffloaded},

          // Server callbacks
          {"onServerRegistered", "(IIJJ)V", &method_onServerRegistered},
          {"onClientConnected", "(Ljava/lang/String;IZII)V", &method_onClientConnected},
          {"onServiceAdded", "(IILjava/util/List;)V", &method_onServiceAdded},
          {"onServiceDeleted", "(III)V", &method_onServiceDeleted},
          {"onResponseSendCompleted", "(II)V", &method_onResponseSendCompleted},
          {"onServerReadCharacteristic", "(Ljava/lang/String;IIIIZ)V",
           &method_onServerReadCharacteristic},
          {"onServerReadDescriptor", "(Ljava/lang/String;IIIIZ)V", &method_onServerReadDescriptor},
          {"onServerWriteCharacteristic", "(Ljava/lang/String;IIIIIZZ[B)V",
           &method_onServerWriteCharacteristic},
          {"onServerWriteDescriptor", "(Ljava/lang/String;IIIIIZZ[B)V",
           &method_onServerWriteDescriptor},
          {"onExecuteWrite", "(Ljava/lang/String;III)V", &method_onExecuteWrite},
          {"onNotificationSent", "(II)V", &method_onNotificationSent},
          {"onServerCongestion", "(IZ)V", &method_onServerCongestion},
          {"onMtuChanged", "(II)V", &method_onServerMtuChanged},
          {"onServerPhyRead", "(ILjava/lang/String;III)V", &method_onServerPhyRead},
          {"onServerPhyUpdate", "(IIII)V", &method_onServerPhyUpdate},
          {"onServerConnUpdate", "(IIIII)V", &method_onServerConnUpdate},
          {"onServerSubrateChange", "(IIIIIII)V", &method_onServerSubrateChange},
          {"onServerCharacteristicsUnoffloaded", "(III)V",
           &method_onServerCharacteristicsUnoffloaded},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/gatt/GattNativeCallback", javaMethods);
  return 0;
}

int register_com_android_bluetooth_gatt(JNIEnv* env) {
  const std::array<std::function<int(JNIEnv*)>, 3> register_fns = {
          register_com_android_bluetooth_gatt_advertise_manager,
          register_com_android_bluetooth_gatt_distance_measurement,
          register_com_android_bluetooth_gatt_,
  };

  for (const auto& fn : register_fns) {
    const int result = fn(env);
    if (result != 0) {
      return result;
    }
  }
  return 0;
}
}  // namespace android
