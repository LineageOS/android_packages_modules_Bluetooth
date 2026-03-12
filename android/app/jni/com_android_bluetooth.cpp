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

#define LOG_TAG "bluetooth-jni"

#include "com_android_bluetooth.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/scoped_local_ref.h>

namespace android {

RawAddress addressFromJByteArray(JNIEnv* env, jbyteArray object) {
  jbyte* address_bytes = env->GetByteArrayElements(object, nullptr);
  log::assert_that(address_bytes != nullptr, "null byte address");
  RawAddress address = RawAddress::FromOctets(reinterpret_cast<const uint8_t*>(address_bytes));
  env->ReleaseByteArrayElements(object, address_bytes, 0);
  return address;
}

ScopedLocalRef<jbyteArray> addressToJByteArray(const CallbackEnv& env, RawAddress address) {
  ScopedLocalRef<jbyteArray> object(env.get(), env->NewByteArray(RawAddress::kLength));
  log::assert_that(object.get() != nullptr, "null jbyte array allocation");
  // SetByteArrayRegion performs a copy of the original buffer and is safe
  // to use with a local reference to the address data.
  env->SetByteArrayRegion(object.get(), 0, RawAddress::kLength,
                          reinterpret_cast<jbyte*>(address.address.data()));
  return object;
}

ScopedLocalRef<jstring> addressToJString(const CallbackEnv& env, RawAddress address) {
  char address_cstr[32];
  snprintf(address_cstr, sizeof(address_cstr), "%02X:%02X:%02X:%02X:%02X:%02X", address.address[0],
           address.address[1], address.address[2], address.address[3], address.address[4],
           address.address[5]);
  // NewStringUTF performs a copy of the original buffer and is safe to use with
  // a local reference to the address string.
  ScopedLocalRef<jstring> object(env.get(), env->NewStringUTF(address_cstr));
  log::assert_that(object.get() != nullptr, "null jstring allocation");
  return object;
}

std::string stringFromJstring(JNIEnv* env, const jstring object) {
  const char* string_char = env->GetStringUTFChars(object, nullptr);
  log::assert_that(string_char != nullptr, "null string");
  std::string cpp_string = std::string(string_char);
  env->ReleaseStringUTFChars(object, string_char);
  return cpp_string;
}

}  // namespace android
