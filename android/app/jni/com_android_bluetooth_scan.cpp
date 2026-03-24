/*
 * Copyright (C) 2025 The Android Open Source Project
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

#define LOG_TAG "BluetoothScanJni"

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

#include "com_android_bluetooth.h"
#include "com_android_bluetooth_flags.h"
#include "hardware/ble_scanner.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_common_types.h"
#include "main/shim/le_scanning_manager.h"

using bluetooth::Uuid;

static RawAddress str2addr(JNIEnv* env, jstring address) {
  const char* c_address = env->GetStringUTFChars(address, NULL);
  if (!c_address) {
    return RawAddress::kEmpty;
  }

  auto bd_addr = RawAddress::FromString(std::string(c_address));
  env->ReleaseStringUTFChars(address, c_address);

  return bd_addr.value_or(RawAddress::kEmpty);
}

namespace android {

/**
 * Scanner callback methods
 */
static jmethodID method_onScannerRegistered;
static jmethodID method_onScanResult;
static jmethodID method_onScanFilterConfig;
static jmethodID method_onScanFilterParamsConfigured;
static jmethodID method_onScanFilterEnableDisabled;
static jmethodID method_onBatchScanStorageConfigured;
static jmethodID method_onBatchScanStartStopped;
static jmethodID method_onBatchScanReports;
static jmethodID method_onBatchScanThresholdCrossed;
static jmethodID method_createOnTrackAdvFoundLostObject;
static jmethodID method_onTrackAdvFoundLost;
static jmethodID method_onScanParamSetupCompleted;
static jmethodID method_onMsftAdvMonitorAdd;
static jmethodID method_onMsftAdvMonitorRemove;
static jmethodID method_onMsftAdvMonitorEnable;

/**
 * Periodic scanner callback methods
 */
static jmethodID method_onSyncLost;
static jmethodID method_onSyncReport;
static jmethodID method_onSyncStarted;
static jmethodID method_onSyncTransferredCallback;
static jmethodID method_onBigInfoReport;

/** Pointer to the LE scanner interface methods.*/
static BleScannerInterface* sScanner = NULL;
static jobject mScanCallbacksObj = NULL;
static jfieldID sScanCallbacksField;
static jobject mPeriodicScanCallbacksObj = NULL;
static jfieldID sPeriodicScanCallbacksField;
static std::shared_mutex callbacks_mutex;

class JniScanningCallbacks : ScanningCallbacks {
public:
  static ScanningCallbacks* GetInstance() {
    static ScanningCallbacks* instance = new JniScanningCallbacks();
    return instance;
  }

  void OnScannerRegistered(const Uuid app_uuid, uint8_t scannerId, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onScannerRegistered, status, scannerId,
                                 app_uuid.msb(), app_uuid.lsb());
  }

  void OnSetScannerParameterComplete(uint8_t scannerId, uint8_t status) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onScanParamSetupCompleted, status,
                                 scannerId);
  }

  void OnScanResult(uint16_t event_type, uint8_t addr_type, RawAddress bda, uint8_t primary_phy,
                    uint8_t secondary_phy, uint8_t advertising_sid, int8_t tx_power, int8_t rssi,
                    uint16_t periodic_adv_int, std::vector<uint8_t> adv_data) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
      return;
    }

    ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, bda);
    ScopedLocalRef<jbyteArray> jb(sCallbackEnv.get(), sCallbackEnv->NewByteArray(adv_data.size()));
    sCallbackEnv->SetByteArrayRegion(jb.get(), 0, adv_data.size(), (jbyte*)adv_data.data());

    // TODO(optedoblivion): Figure out original address for here, use empty
    // for now

    // length of data + '\0'
    char empty_address[18] = "00:00:00:00:00:00";
    ScopedLocalRef<jstring> fake_address(sCallbackEnv.get(),
                                         sCallbackEnv->NewStringUTF(empty_address));

    sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onScanResult, event_type, addr_type,
                                 address.get(), primary_phy, secondary_phy, advertising_sid,
                                 tx_power, rssi, periodic_adv_int, jb.get(), fake_address.get());
  }

  void OnTrackAdvFoundLost(AdvertisingTrackInfo track_info) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
      log::error("sCallbackEnv not valid or no mScanCallbacksObj.");
      return;
    }

    ScopedLocalRef<jstring> address = addressToJString(sCallbackEnv, track_info.advertiser_address);

    ScopedLocalRef<jbyteArray> jb_adv_pkt(sCallbackEnv.get(),
                                          sCallbackEnv->NewByteArray(track_info.adv_packet_len));
    ScopedLocalRef<jbyteArray> jb_scan_rsp(
            sCallbackEnv.get(), sCallbackEnv->NewByteArray(track_info.scan_response_len));

    sCallbackEnv->SetByteArrayRegion(jb_adv_pkt.get(), 0, track_info.adv_packet_len,
                                     (jbyte*)track_info.adv_packet.data());

    sCallbackEnv->SetByteArrayRegion(jb_scan_rsp.get(), 0, track_info.scan_response_len,
                                     (jbyte*)track_info.scan_response.data());

    ScopedLocalRef<jobject> trackadv_obj(
            sCallbackEnv.get(),
            sCallbackEnv->CallObjectMethod(
                    mScanCallbacksObj, method_createOnTrackAdvFoundLostObject,
                    track_info.scanner_id, track_info.adv_packet_len, jb_adv_pkt.get(),
                    track_info.scan_response_len, jb_scan_rsp.get(), track_info.filter_index,
                    track_info.advertiser_state, track_info.advertiser_info_present, address.get(),
                    track_info.advertiser_address_type, track_info.tx_power, track_info.rssi,
                    track_info.time_stamp));

    if (NULL != trackadv_obj.get()) {
      sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onTrackAdvFoundLost,
                                   trackadv_obj.get());
    }
  }

  void OnBatchScanReports(int client_if, int status, int report_format, int num_records,
                          std::vector<uint8_t> data) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
      return;
    }
    ScopedLocalRef<jbyteArray> jb(sCallbackEnv.get(), sCallbackEnv->NewByteArray(data.size()));
    sCallbackEnv->SetByteArrayRegion(jb.get(), 0, data.size(), (jbyte*)data.data());

    sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onBatchScanReports, status, client_if,
                                 report_format, num_records, jb.get());
  }

  void OnBatchScanThresholdCrossed(int client_if) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
      return;
    }
    sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onBatchScanThresholdCrossed, client_if);
  }

  void OnPeriodicSyncStarted(int reg_id, uint8_t status, uint16_t sync_handle, uint8_t sid,
                             uint8_t address_type, RawAddress address, uint8_t phy,
                             uint16_t interval) override {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid()) {
      return;
    }
    if (!mPeriodicScanCallbacksObj) {
      log::error("mPeriodicScanCallbacksObj is NULL. Return.");
      return;
    }
    ScopedLocalRef<jstring> addr = addressToJString(sCallbackEnv, address);

    sCallbackEnv->CallVoidMethod(mPeriodicScanCallbacksObj, method_onSyncStarted, reg_id,
                                 sync_handle, sid, address_type, addr.get(), phy, interval, status);
  }

  void OnPeriodicSyncReport(uint16_t sync_handle, int8_t tx_power, int8_t rssi, uint8_t data_status,
                            std::vector<uint8_t> data) override {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mPeriodicScanCallbacksObj) {
      return;
    }

    ScopedLocalRef<jbyteArray> jb(sCallbackEnv.get(), sCallbackEnv->NewByteArray(data.size()));
    sCallbackEnv->SetByteArrayRegion(jb.get(), 0, data.size(), (jbyte*)data.data());

    sCallbackEnv->CallVoidMethod(mPeriodicScanCallbacksObj, method_onSyncReport, sync_handle,
                                 tx_power, rssi, data_status, jb.get());
  }

  void OnPeriodicSyncLost(uint16_t sync_handle) override {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || !mPeriodicScanCallbacksObj) {
      return;
    }

    sCallbackEnv->CallVoidMethod(mPeriodicScanCallbacksObj, method_onSyncLost, sync_handle);
  }

  void OnPeriodicSyncTransferred(int pa_source, uint8_t status, RawAddress address) override {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid()) {
      return;
    }
    if (!mPeriodicScanCallbacksObj) {
      log::error("mPeriodicScanCallbacksObj is NULL. Return.");
      return;
    }
    ScopedLocalRef<jstring> addr = addressToJString(sCallbackEnv, address);

    sCallbackEnv->CallVoidMethod(mPeriodicScanCallbacksObj, method_onSyncTransferredCallback,
                                 pa_source, status, addr.get());
  }

  void OnBigInfoReport(uint16_t sync_handle, bool encrypted) {
    std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid()) {
      return;
    }

    if (!mPeriodicScanCallbacksObj) {
      log::error("mPeriodicScanCallbacksObj is NULL. Return.");
      return;
    }
    sCallbackEnv->CallVoidMethod(mPeriodicScanCallbacksObj, method_onBigInfoReport, sync_handle,
                                 encrypted);
  }
};

/**
 * Native Client functions
 */

static void registerScannerNative(JNIEnv* /* env */, jobject /* object */, jlong app_uuid_msb,
                                  jlong app_uuid_lsb) {
  if (!sScanner) {
    return;
  }
  Uuid uuid(app_uuid_msb, app_uuid_lsb);
  sScanner->RegisterScanner(uuid);
}

static void unregisterScannerNative(JNIEnv* /* env */, jobject /* object */, jint scanner_id) {
  if (!sScanner) {
    return;
  }

  sScanner->Unregister(scanner_id);
}

static void scanNative(JNIEnv* /* env */, jobject /* object */, jboolean start) {
  if (!sScanner) {
    return;
  }
  sScanner->Scan(start);
}

static void setScanParametersNative(JNIEnv* /* env */, jobject /* object */, jint client_if_1m,
                                    jint scan_interval_unit_1m, jint scan_window_unit_1m,
                                    jint client_if_coded, jint scan_interval_unit_coded,
                                    jint scan_window_unit_coded, jint scan_phy) {
  if (!sScanner) {
    return;
  }
  sScanner->SetScanParameters(/* use active scan */ 0x01, client_if_1m, scan_interval_unit_1m,
                              scan_window_unit_1m, client_if_coded, scan_interval_unit_coded,
                              scan_window_unit_coded, scan_phy);
}

static void scan_filter_param_cb(uint8_t client_if, uint8_t avbl_space, uint8_t action,
                                 uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onScanFilterParamsConfigured, action,
                               status, client_if, avbl_space);
}

static void scanFilterParamAddNative(JNIEnv* env, jobject /* object */, jobject params) {
  if (!sScanner) {
    return;
  }
  const int add_scan_filter_params_action = 0;
  auto filt_params = std::make_unique<btgatt_filt_param_setup_t>();

  jfieldID fieldId = 0;
  ScopedLocalRef<jclass> filtparam(env, env->GetObjectClass(params));

  fieldId = env->GetFieldID(filtparam.get(), "clientInterface", "I");
  uint8_t client_if = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "filterIndex", "I");
  uint8_t filt_index = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "featureSelection", "I");
  filt_params->feat_seln = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "listLogicType", "I");
  filt_params->list_logic_type = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "filterLogicType", "I");
  filt_params->filt_logic_type = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "delayMode", "I");
  filt_params->dely_mode = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "foundTimeout", "I");
  filt_params->found_timeout = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "lostTimeout", "I");
  filt_params->lost_timeout = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "foundTimeoutCount", "I");
  filt_params->found_timeout_cnt = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "numberOfTrackEntries", "I");
  filt_params->num_of_tracking_entries = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "rssiHighValue", "I");
  filt_params->rssi_high_thres = env->GetIntField(params, fieldId);

  fieldId = env->GetFieldID(filtparam.get(), "rssiLowValue", "I");
  filt_params->rssi_low_thres = env->GetIntField(params, fieldId);

  sScanner->ScanFilterParamSetup(client_if, add_scan_filter_params_action, filt_index,
                                 std::move(filt_params),
                                 base::Bind(&scan_filter_param_cb, client_if));
}

static void scanFilterParamDeleteNative(JNIEnv* /* env */, jobject /* object */, jint client_if,
                                        jint filt_index) {
  if (!sScanner) {
    return;
  }
  const int delete_scan_filter_params_action = 1;
  sScanner->ScanFilterParamSetup(client_if, delete_scan_filter_params_action, filt_index, nullptr,
                                 base::Bind(&scan_filter_param_cb, client_if));
}

static void scan_filter_cfg_cb(uint8_t client_if, uint8_t filt_type, uint8_t avbl_space,
                               uint8_t action, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onScanFilterConfig, action, status,
                               client_if, filt_type, avbl_space);
}

static void scanFilterAddNative(JNIEnv* env, jobject /* object */, jint client_if,
                                jobjectArray filters, jint filter_index) {
  if (!sScanner) {
    return;
  }

  jmethodID uuidGetMsb;
  jmethodID uuidGetLsb;

  const JNIJavaMethod javaMethods[] = {
          {"getMostSignificantBits", "()J", &uuidGetMsb},
          {"getLeastSignificantBits", "()J", &uuidGetLsb},
  };
  GET_JAVA_METHODS(env, "java/util/UUID", javaMethods);

  std::vector<ApcfCommand> native_filters;

  int numFilters = env->GetArrayLength(filters);
  if (numFilters == 0) {
    sScanner->ScanFilterAdd(filter_index, std::move(native_filters),
                            base::Bind(&scan_filter_cfg_cb, client_if));
    return;
  }

  jclass entryClazz = env->GetObjectClass(env->GetObjectArrayElement(filters, 0));

  jfieldID typeFid = env->GetFieldID(entryClazz, "type", "B");
  jfieldID addressFid = env->GetFieldID(entryClazz, "address", "Ljava/lang/String;");
  jfieldID addrTypeFid = env->GetFieldID(entryClazz, "addr_type", "B");
  jfieldID irkTypeFid = env->GetFieldID(entryClazz, "irk", "[B");
  jfieldID uuidFid = env->GetFieldID(entryClazz, "uuid", "Ljava/util/UUID;");
  jfieldID uuidMaskFid = env->GetFieldID(entryClazz, "uuid_mask", "Ljava/util/UUID;");
  jfieldID nameFid = env->GetFieldID(entryClazz, "name", "Ljava/lang/String;");
  jfieldID companyFid = env->GetFieldID(entryClazz, "company", "I");
  jfieldID companyMaskFid = env->GetFieldID(entryClazz, "company_mask", "I");
  jfieldID adTypeFid = env->GetFieldID(entryClazz, "ad_type", "I");
  jfieldID dataFid = env->GetFieldID(entryClazz, "data", "[B");
  jfieldID dataMaskFid = env->GetFieldID(entryClazz, "data_mask", "[B");
  jfieldID orgFid = env->GetFieldID(entryClazz, "org_id", "I");
  jfieldID TDSFlagsFid = env->GetFieldID(entryClazz, "tds_flags", "I");
  jfieldID TDSFlagsMaskFid = env->GetFieldID(entryClazz, "tds_flags_mask", "I");
  jfieldID metaDataTypeFid = env->GetFieldID(entryClazz, "meta_data_type", "I");
  jfieldID metaDataFid = env->GetFieldID(entryClazz, "meta_data", "[B");

  for (int i = 0; i < numFilters; ++i) {
    ApcfCommand curr{};

    ScopedLocalRef<jobject> current(env, env->GetObjectArrayElement(filters, i));

    curr.type = env->GetByteField(current.get(), typeFid);

    ScopedLocalRef<jstring> address(env, (jstring)env->GetObjectField(current.get(), addressFid));
    if (address.get() != NULL) {
      curr.address = str2addr(env, address.get());
    }

    curr.addr_type = env->GetByteField(current.get(), addrTypeFid);

    ScopedLocalRef<jbyteArray> irkByteArray(
            env, (jbyteArray)env->GetObjectField(current.get(), irkTypeFid));

    if (irkByteArray.get() != nullptr) {
      int len = env->GetArrayLength(irkByteArray.get());
      // IRK is 128 bits or 16 octets, set the bytes or zero it out
      if (len != 16) {
        log::error("Invalid IRK length '{}'; expected 16", len);
        jniThrowIOException(env, EINVAL);
        return;
      }
      jbyte* irkBytes = env->GetByteArrayElements(irkByteArray.get(), NULL);
      if (irkBytes == NULL) {
        jniThrowIOException(env, EINVAL);
        return;
      }
      for (int j = 0; j < len; j++) {
        curr.irk[j] = irkBytes[j];
      }
      env->ReleaseByteArrayElements(irkByteArray.get(), irkBytes, JNI_ABORT);
    }

    ScopedLocalRef<jobject> uuid(env, env->GetObjectField(current.get(), uuidFid));
    if (uuid.get() != NULL) {
      jlong uuid_msb = env->CallLongMethod(uuid.get(), uuidGetMsb);
      jlong uuid_lsb = env->CallLongMethod(uuid.get(), uuidGetLsb);
      curr.uuid = Uuid(uuid_msb, uuid_lsb);
    }

    ScopedLocalRef<jobject> uuid_mask(env, env->GetObjectField(current.get(), uuidMaskFid));
    if (uuid.get() != NULL) {
      jlong uuid_msb = env->CallLongMethod(uuid_mask.get(), uuidGetMsb);
      jlong uuid_lsb = env->CallLongMethod(uuid_mask.get(), uuidGetLsb);
      curr.uuid_mask = Uuid(uuid_msb, uuid_lsb);
    }

    ScopedLocalRef<jstring> name(env, (jstring)env->GetObjectField(current.get(), nameFid));
    if (name.get() != NULL) {
      const char* c_name = env->GetStringUTFChars(name.get(), NULL);
      if (c_name != NULL && strlen(c_name) != 0) {
        curr.name = std::vector<uint8_t>(c_name, c_name + strlen(c_name));
        env->ReleaseStringUTFChars(name.get(), c_name);
      }
    }

    curr.company = env->GetIntField(current.get(), companyFid);

    curr.company_mask = env->GetIntField(current.get(), companyMaskFid);

    curr.ad_type = env->GetIntField(current.get(), adTypeFid);

    ScopedLocalRef<jbyteArray> data(env, (jbyteArray)env->GetObjectField(current.get(), dataFid));
    if (data.get() != NULL) {
      jbyte* data_array = env->GetByteArrayElements(data.get(), 0);
      int data_len = env->GetArrayLength(data.get());
      if (data_array && data_len) {
        curr.data = std::vector<uint8_t>(data_array, data_array + data_len);
        env->ReleaseByteArrayElements(data.get(), data_array, JNI_ABORT);
      }
    }

    ScopedLocalRef<jbyteArray> data_mask(
            env, (jbyteArray)env->GetObjectField(current.get(), dataMaskFid));
    if (data_mask.get() != NULL) {
      jbyte* data_array = env->GetByteArrayElements(data_mask.get(), 0);
      int data_len = env->GetArrayLength(data_mask.get());
      if (data_array && data_len) {
        curr.data_mask = std::vector<uint8_t>(data_array, data_array + data_len);
        env->ReleaseByteArrayElements(data_mask.get(), data_array, JNI_ABORT);
      }
    }
    curr.org_id = env->GetIntField(current.get(), orgFid);
    curr.tds_flags = env->GetIntField(current.get(), TDSFlagsFid);
    curr.tds_flags_mask = env->GetIntField(current.get(), TDSFlagsMaskFid);
    curr.meta_data_type = env->GetIntField(current.get(), metaDataTypeFid);

    ScopedLocalRef<jbyteArray> meta_data(
            env, (jbyteArray)env->GetObjectField(current.get(), metaDataFid));
    if (meta_data.get() != NULL) {
      jbyte* data_array = env->GetByteArrayElements(meta_data.get(), 0);
      int data_len = env->GetArrayLength(meta_data.get());
      if (data_array && data_len) {
        curr.meta_data = std::vector<uint8_t>(data_array, data_array + data_len);
        env->ReleaseByteArrayElements(meta_data.get(), data_array, JNI_ABORT);
      }
    }

    native_filters.push_back(curr);
  }

  sScanner->ScanFilterAdd(filter_index, std::move(native_filters),
                          base::Bind(&scan_filter_cfg_cb, client_if));
}

static void scanFilterClearNative(JNIEnv* /* env */, jobject /* object */, jint client_if,
                                  jint filt_index) {
  if (!sScanner) {
    return;
  }
  sScanner->ScanFilterClear(filt_index, base::Bind(&scan_filter_cfg_cb, client_if));
}

static void scan_enable_cb(uint8_t client_if, uint8_t action, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onScanFilterEnableDisabled, action, status,
                               client_if);
}

static void scanFilterEnableNative(JNIEnv* /* env */, jobject /* object */, jint client_if,
                                   jboolean enable) {
  if (!sScanner) {
    return;
  }
  sScanner->ScanFilterEnable(enable, base::Bind(&scan_enable_cb, client_if));
}

static void msft_monitor_add_cb(int filter_index, uint8_t monitor_handle, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onMsftAdvMonitorAdd, filter_index,
                               monitor_handle, status);
}

static void msft_monitor_remove_cb(int filter_index, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onMsftAdvMonitorRemove, filter_index,
                               status);
}

static void msft_monitor_enable_cb(bool enable, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onMsftAdvMonitorEnable, enable, status);
}

static bool isMsftSupportedNative(JNIEnv* /* env */, jobject /* object */) {
  return sScanner && sScanner->IsMsftSupported();
}

static void msftAdvMonitorAddNative(JNIEnv* env, jobject /* object*/, jobject msft_adv_monitor,
                                    jobjectArray msft_adv_monitor_patterns,
                                    jobject msft_adv_monitor_uuid, jobject msft_adv_monitor_address,
                                    jint filter_index) {
  if (!sScanner) {
    return;
  }

  jclass msftAdvMonitorClazz = env->GetObjectClass(msft_adv_monitor);
  jfieldID rssiThresholdHighFid = env->GetFieldID(msftAdvMonitorClazz, "rssi_threshold_high", "B");
  jfieldID rssiThresholdLowFid = env->GetFieldID(msftAdvMonitorClazz, "rssi_threshold_low", "B");
  jfieldID rssiThresholdLowTimeIntervalFid =
          env->GetFieldID(msftAdvMonitorClazz, "rssi_threshold_low_time_interval", "B");
  jfieldID rssiSamplingPeriodFid =
          env->GetFieldID(msftAdvMonitorClazz, "rssi_sampling_period", "B");
  jfieldID conditionTypeFid = env->GetFieldID(msftAdvMonitorClazz, "condition_type", "B");

  MsftAdvMonitor native_msft_adv_monitor{};
  ScopedLocalRef<jobject> msft_adv_monitor_object(env, msft_adv_monitor);
  native_msft_adv_monitor.rssi_threshold_high =
          env->GetByteField(msft_adv_monitor_object.get(), rssiThresholdHighFid);
  native_msft_adv_monitor.rssi_threshold_low =
          env->GetByteField(msft_adv_monitor_object.get(), rssiThresholdLowFid);
  native_msft_adv_monitor.rssi_threshold_low_time_interval =
          env->GetByteField(msft_adv_monitor_object.get(), rssiThresholdLowTimeIntervalFid);
  native_msft_adv_monitor.rssi_sampling_period =
          env->GetByteField(msft_adv_monitor_object.get(), rssiSamplingPeriodFid);
  native_msft_adv_monitor.condition_type =
          env->GetByteField(msft_adv_monitor_object.get(), conditionTypeFid);

  if (native_msft_adv_monitor.condition_type == MSFT_CONDITION_TYPE_ADDRESS) {
    jclass msftAdvMonitorAddressClazz = env->GetObjectClass(msft_adv_monitor_address);
    jfieldID addrTypeFid = env->GetFieldID(msftAdvMonitorAddressClazz, "addr_type", "B");
    jfieldID bdAddrFid =
            env->GetFieldID(msftAdvMonitorAddressClazz, "bd_addr", "Ljava/lang/String;");

    MsftAdvMonitorAddress native_msft_adv_monitor_address{};
    ScopedLocalRef<jobject> msft_adv_monitor_address_object(env, msft_adv_monitor_address);
    native_msft_adv_monitor_address.addr_type =
            env->GetByteField(msft_adv_monitor_address_object.get(), addrTypeFid);
    native_msft_adv_monitor_address.bd_addr = str2addr(
            env, (jstring)env->GetObjectField(msft_adv_monitor_address_object.get(), bdAddrFid));

    native_msft_adv_monitor.addr_info = native_msft_adv_monitor_address;

    sScanner->MsftAdvMonitorAdd(std::move(native_msft_adv_monitor),
                                base::Bind(&msft_monitor_add_cb, filter_index));
    return;
  }

  if (native_msft_adv_monitor.condition_type == MSFT_CONDITION_TYPE_UUID) {
    jclass msftAdvMonitorUuidClazz = env->GetObjectClass(msft_adv_monitor_uuid);
    jfieldID uuidFid = env->GetFieldID(msftAdvMonitorUuidClazz, "uuid", "[B");

    MsftAdvMonitorUuid native_msft_adv_monitor_uuid{};
    ScopedLocalRef<jobject> msft_adv_monitor_uuid_object(env, msft_adv_monitor_uuid);

    ScopedLocalRef<jbyteArray> uuidByteArray(
            env, (jbyteArray)env->GetObjectField(msft_adv_monitor_uuid_object.get(), uuidFid));
    if (uuidByteArray.get() == nullptr) {
      log::error("Cannot obtain uuid byte array.");
      jniThrowIOException(env, EINVAL);
      return;
    }

    jbyte* uuidBytes = env->GetByteArrayElements(uuidByteArray.get(), NULL);
    if (uuidBytes == NULL) {
      log::error("Cannot obtain uuid bytes.");
      jniThrowIOException(env, EINVAL);
      return;
    }

    native_msft_adv_monitor_uuid.uuid.resize(env->GetArrayLength(uuidByteArray.get()));
    std::copy(uuidBytes, uuidBytes + env->GetArrayLength(uuidByteArray.get()),
              native_msft_adv_monitor_uuid.uuid.begin());

    env->ReleaseByteArrayElements(uuidByteArray.get(), uuidBytes, 0);

    native_msft_adv_monitor.uuid_info = native_msft_adv_monitor_uuid;

    sScanner->MsftAdvMonitorAdd(std::move(native_msft_adv_monitor),
                                base::Bind(&msft_monitor_add_cb, filter_index));
    return;
  }

  jclass msftAdvMonitorPatternClazz =
          env->GetObjectClass(env->GetObjectArrayElement(msft_adv_monitor_patterns, 0));
  jfieldID adTypeFid = env->GetFieldID(msftAdvMonitorPatternClazz, "ad_type", "B");
  jfieldID startByteFid = env->GetFieldID(msftAdvMonitorPatternClazz, "start_byte", "B");
  jfieldID patternFid = env->GetFieldID(msftAdvMonitorPatternClazz, "pattern", "[B");

  int numPatterns = env->GetArrayLength(msft_adv_monitor_patterns);
  std::vector<MsftAdvMonitorPattern> patterns;

  for (int i = 0; i < numPatterns; i++) {
    MsftAdvMonitorPattern native_msft_adv_monitor_pattern{};
    ScopedLocalRef<jobject> msft_adv_monitor_pattern_object(
            env, env->GetObjectArrayElement(msft_adv_monitor_patterns, i));
    native_msft_adv_monitor_pattern.ad_type =
            env->GetByteField(msft_adv_monitor_pattern_object.get(), adTypeFid);
    native_msft_adv_monitor_pattern.start_byte =
            env->GetByteField(msft_adv_monitor_pattern_object.get(), startByteFid);

    ScopedLocalRef<jbyteArray> patternByteArray(
            env,
            (jbyteArray)env->GetObjectField(msft_adv_monitor_pattern_object.get(), patternFid));
    if (patternByteArray.get() != nullptr) {
      jbyte* patternBytes = env->GetByteArrayElements(patternByteArray.get(), NULL);
      if (patternBytes == NULL) {
        jniThrowIOException(env, EINVAL);
        return;
      }
      for (int j = 0; j < env->GetArrayLength(patternByteArray.get()); j++) {
        native_msft_adv_monitor_pattern.pattern.push_back(patternBytes[j]);
      }
      env->ReleaseByteArrayElements(patternByteArray.get(), patternBytes, 0);
    }

    patterns.push_back(native_msft_adv_monitor_pattern);
  }
  native_msft_adv_monitor.patterns = patterns;

  sScanner->MsftAdvMonitorAdd(std::move(native_msft_adv_monitor),
                              base::Bind(&msft_monitor_add_cb, filter_index));
}

static void msftAdvMonitorRemoveNative(JNIEnv* /* env */, jobject /* object */, int filter_index,
                                       int monitor_handle) {
  if (!sScanner) {
    return;
  }
  sScanner->MsftAdvMonitorRemove(monitor_handle, base::Bind(&msft_monitor_remove_cb, filter_index));
}

static void msftAdvMonitorEnableNative(JNIEnv* /* env */, jobject /* object */, jboolean enable) {
  if (!sScanner) {
    return;
  }
  sScanner->MsftAdvMonitorEnable(enable, base::Bind(&msft_monitor_enable_cb));
}

static void batch_scan_cfg_storage_cb(uint8_t client_if, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onBatchScanStorageConfigured, status,
                               client_if);
}

static void configBatchScanStorageNative(JNIEnv* /* env */, jobject /* object */, jint client_if,
                                         jint max_full_reports_percent,
                                         jint max_trunc_reports_percent,
                                         jint notify_threshold_level_percent) {
  if (!sScanner) {
    return;
  }
  sScanner->BatchScanConfigStorage(client_if, max_full_reports_percent, max_trunc_reports_percent,
                                   notify_threshold_level_percent,
                                   base::Bind(&batch_scan_cfg_storage_cb, client_if));
}

static void batch_scan_enable_cb(uint8_t client_if, uint8_t status) {
  std::shared_lock<std::shared_mutex> lock(callbacks_mutex);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid() || !mScanCallbacksObj) {
    return;
  }
  sCallbackEnv->CallVoidMethod(mScanCallbacksObj, method_onBatchScanStartStopped, 0 /* unused */,
                               status, client_if);
}

static void startBatchScanNative(JNIEnv* /* env */, jobject /* object */, jint client_if,
                                 jint scan_mode, jint scan_interval_unit, jint scan_window_unit,
                                 jint addr_type, jint discard_rule) {
  if (!sScanner) {
    return;
  }
  sScanner->BatchScanEnable(scan_mode, scan_interval_unit, scan_window_unit, addr_type,
                            discard_rule, base::Bind(&batch_scan_enable_cb, client_if));
}

static void stopBatchScanNative(JNIEnv* /* env */, jobject /* object */, jint client_if) {
  if (!sScanner) {
    return;
  }
  sScanner->BatchScanDisable(base::Bind(&batch_scan_enable_cb, client_if));
}

static void readScanReportsNative(JNIEnv* /* env */, jobject /* object */, jint client_if,
                                  jint scan_type) {
  if (!sScanner) {
    return;
  }
  sScanner->BatchScanReadReports(client_if, scan_type);
}

static void periodicScanInitializeNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);
  if (mPeriodicScanCallbacksObj != NULL) {
    log::warn("Cleaning up periodic scan callback object");
    env->DeleteGlobalRef(mPeriodicScanCallbacksObj);
    mPeriodicScanCallbacksObj = NULL;
  }

  if ((mPeriodicScanCallbacksObj = env->NewGlobalRef(
               env->GetObjectField(object, sPeriodicScanCallbacksField))) == nullptr) {
    log::fatal("Failed to allocate Global Ref for Periodic Scan Callbacks");
  }
}

static void periodicScanCleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);
  if (mPeriodicScanCallbacksObj != NULL) {
    env->DeleteGlobalRef(mPeriodicScanCallbacksObj);
    mPeriodicScanCallbacksObj = NULL;
  }
}

static void scanInitializeNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);

  sScanner = bluetooth::shim::get_ble_scanner_instance();
  sScanner->RegisterCallbacks(JniScanningCallbacks::GetInstance());

  if (mScanCallbacksObj != NULL) {
    log::warn("Cleaning up scan callback object");
    env->DeleteGlobalRef(mScanCallbacksObj);
    mScanCallbacksObj = NULL;
  }

  if ((mScanCallbacksObj = env->NewGlobalRef(env->GetObjectField(object, sScanCallbacksField))) ==
      nullptr) {
    log::fatal("Failed to allocate Global Ref for Scan Callbacks");
  }
}

static void scanCleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_mutex> lock(callbacks_mutex);
  if (mScanCallbacksObj != NULL) {
    env->DeleteGlobalRef(mScanCallbacksObj);
    mScanCallbacksObj = NULL;
  }
  if (sScanner != NULL) {
    sScanner = NULL;
  }
}

static void startSyncNative(JNIEnv* env, jobject /* object */, jint sid, jstring address,
                            jint addressType, jint skip, jint timeout, jint reg_id) {
  if (!sScanner) {
    return;
  }
  sScanner->StartSync(sid, str2addr(env, address), addressType, skip, timeout, reg_id);
}

static void stopSyncNative(JNIEnv* /* env */, jobject /* object */, jint sync_handle) {
  if (!sScanner) {
    return;
  }
  sScanner->StopSync(sync_handle);
}

static void cancelSyncNative(JNIEnv* env, jobject /* object */, jint sid, jstring address) {
  if (!sScanner) {
    return;
  }
  sScanner->CancelCreateSync(sid, str2addr(env, address));
}

static void syncTransferNative(JNIEnv* env, jobject /* object */, jint pa_source, jstring addr,
                               jint service_data, jint sync_handle) {
  if (!sScanner) {
    return;
  }
  sScanner->TransferSync(str2addr(env, addr), service_data, sync_handle, pa_source);
}

static void transferSetInfoNative(JNIEnv* env, jobject /* object */, jint pa_source, jstring addr,
                                  jint service_data, jint adv_handle) {
  if (!sScanner) {
    return;
  }
  sScanner->TransferSetInfo(str2addr(env, addr), service_data, adv_handle, pa_source);
}

// JNI functions defined in ScanNativeInterface
static int register_com_android_bluetooth_scan_(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initializeNative", "()V", (void*)scanInitializeNative},
          {"cleanupNative", "()V", (void*)scanCleanupNative},
          {"registerScannerNative", "(JJ)V", (void*)registerScannerNative},
          {"unregisterScannerNative", "(I)V", (void*)unregisterScannerNative},
          {"scanNative", "(Z)V", (void*)scanNative},
          // Batch scan JNI functions.
          {"configBatchScanStorageNative", "(IIII)V", (void*)configBatchScanStorageNative},
          {"startBatchScanNative", "(IIIIII)V", (void*)startBatchScanNative},
          {"stopBatchScanNative", "(I)V", (void*)stopBatchScanNative},
          {"readScanReportsNative", "(II)V", (void*)readScanReportsNative},
          // Scan filter JNI functions.
          {"scanFilterParamAddNative", "(Lcom/android/bluetooth/le_scan/FilterParams;)V",
           (void*)scanFilterParamAddNative},
          {"scanFilterParamDeleteNative", "(II)V", (void*)scanFilterParamDeleteNative},
          {"scanFilterAddNative", "(I[Lcom/android/bluetooth/le_scan/ScanFilterQueue$Entry;I)V",
           (void*)scanFilterAddNative},
          {"scanFilterClearNative", "(II)V", (void*)scanFilterClearNative},
          {"scanFilterEnableNative", "(IZ)V", (void*)scanFilterEnableNative},
          {"setScanParametersNative", "(IIIIIII)V", (void*)setScanParametersNative},
          // MSFT HCI Extension functions.
          {"isMsftSupportedNative", "()Z", (bool*)isMsftSupportedNative},
          {"msftAdvMonitorAddNative",
           "(Lcom/android/bluetooth/le_scan/MsftAdvMonitor$Monitor;[Lcom/android/bluetooth/le_scan/"
           "MsftAdvMonitor$Pattern;Lcom/android/bluetooth/le_scan/MsftAdvMonitor$Uuid;Lcom/android/"
           "bluetooth/le_scan/MsftAdvMonitor$Address;I)V",
           (void*)msftAdvMonitorAddNative},
          {"msftAdvMonitorRemoveNative", "(II)V", (void*)msftAdvMonitorRemoveNative},
          {"msftAdvMonitorEnableNative", "(Z)V", (void*)msftAdvMonitorEnableNative},
  };
  const char* jniNativeInterfaceClass = "com/android/bluetooth/le_scan/ScanNativeInterface";
  const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
  if (result != 0) {
    return result;
  }

  sScanCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

  // Client callback functions defined in ScanNativeCallback
  const JNIJavaMethod javaMethods[] = {
          {"onScannerRegistered", "(IIJJ)V", &method_onScannerRegistered},
          {"onScanResult", "(IILjava/lang/String;IIIIII[BLjava/lang/String;)V",
           &method_onScanResult},
          {"onScanFilterConfig", "(IIIII)V", &method_onScanFilterConfig},
          {"onScanFilterParamsConfigured", "(IIII)V", &method_onScanFilterParamsConfigured},
          {"onScanFilterEnableDisabled", "(III)V", &method_onScanFilterEnableDisabled},
          {"onBatchScanStorageConfigured", "(II)V", &method_onBatchScanStorageConfigured},
          {"onBatchScanStartStopped", "(III)V", &method_onBatchScanStartStopped},
          {"onBatchScanReports", "(IIII[B)V", &method_onBatchScanReports},
          {"onBatchScanThresholdCrossed", "(I)V", &method_onBatchScanThresholdCrossed},
          {"createOnTrackAdvFoundLostObject",
           "(II[BI[BIIILjava/lang/String;IIII)"
           "Lcom/android/bluetooth/le_scan/AdvtFilterOnFoundOnLostInfo;",
           &method_createOnTrackAdvFoundLostObject},
          {"onTrackAdvFoundLost", "(Lcom/android/bluetooth/le_scan/AdvtFilterOnFoundOnLostInfo;)V",
           &method_onTrackAdvFoundLost},
          {"onScanParamSetupCompleted", "(II)V", &method_onScanParamSetupCompleted},
          {"onMsftAdvMonitorAdd", "(III)V", &method_onMsftAdvMonitorAdd},
          {"onMsftAdvMonitorRemove", "(II)V", &method_onMsftAdvMonitorRemove},
          {"onMsftAdvMonitorEnable", "(ZI)V", &method_onMsftAdvMonitorEnable},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/le_scan/ScanNativeCallback", javaMethods);
  return 0;
}

// JNI functions defined in PeriodicScanNativeInterface
static int register_com_android_bluetooth_periodic_scan(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initializeNative", "()V", (void*)periodicScanInitializeNative},
          {"cleanupNative", "()V", (void*)periodicScanCleanupNative},
          {"startSyncNative", "(ILjava/lang/String;IIII)V", (void*)startSyncNative},
          {"stopSyncNative", "(I)V", (void*)stopSyncNative},
          {"cancelSyncNative", "(ILjava/lang/String;)V", (void*)cancelSyncNative},
          {"syncTransferNative", "(ILjava/lang/String;II)V", (void*)syncTransferNative},
          {"transferSetInfoNative", "(ILjava/lang/String;II)V", (void*)transferSetInfoNative},
  };
  const char* jniNativeInterfaceClass = "com/android/bluetooth/le_scan/PeriodicScanNativeInterface";
  const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
  if (result != 0) {
    return result;
  }

  sPeriodicScanCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

  // Client callback functions defined in PeriodicScanNativeCallback
  const JNIJavaMethod javaMethods[] = {
          {"onSyncStarted", "(IIIILjava/lang/String;III)V", &method_onSyncStarted},
          {"onSyncReport", "(IIII[B)V", &method_onSyncReport},
          {"onSyncLost", "(I)V", &method_onSyncLost},
          {"onSyncTransferredCallback", "(IILjava/lang/String;)V",
           &method_onSyncTransferredCallback},
          {"onBigInfoReport", "(IZ)V", &method_onBigInfoReport},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/le_scan/PeriodicScanNativeCallback", javaMethods);
  return 0;
}

int register_com_android_bluetooth_scan(JNIEnv* env) {
  const std::array<std::function<int(JNIEnv*)>, 2> register_fns = {
          register_com_android_bluetooth_scan_,
          register_com_android_bluetooth_periodic_scan,
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
